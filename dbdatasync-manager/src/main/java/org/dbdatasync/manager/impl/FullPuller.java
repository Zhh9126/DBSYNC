/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.manager.impl;

import org.dbdatasync.common.model.Paging;
import org.dbdatasync.common.util.BatchTaskUtil;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.NumberUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.manager.AbstractPuller;
import org.dbdatasync.parser.LogService;
import org.dbdatasync.parser.LogType;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.ParserComponent;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.enums.ParserEnum;
import org.dbdatasync.parser.event.FullRefreshEvent;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.model.TableSyncProgress;
import org.dbdatasync.parser.model.Task;
import org.dbdatasync.parser.util.FullTableProgressUtil;
import org.dbdatasync.parser.util.MetaLockUtil;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.util.PrimaryKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 全量同步（表级并发，对齐数据迁移：threadNum 控制表并发，单表内读写串行）。
 *
 * @Version 1.0.0
 * @Author AE86
 * @Date 2020-04-26 15:28
 */
@Component
public final class FullPuller extends AbstractPuller implements ApplicationListener<FullRefreshEvent> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 单表内写批同步执行器（不再使用 threadNum 做写并发）
     */
    private static final Executor SYNC_WRITE_EXECUTOR = Runnable::run;

    private static final AtomicInteger ASYNC_POOL_SEQ = new AtomicInteger(0);

    /**
     * 根据 mapping.syncMode 决定单表内写执行器
     * - SYNC  = Runnable::run（单线程串行，强一致）
     * - ASYNC = 多线程并发写池（reader 读完一批后，写并发执行）
     */
    private Executor getWriteExecutor(Mapping mapping) {
        if (mapping == null || mapping.getParams() == null) {
            return SYNC_WRITE_EXECUTOR;
        }
        String mode = mapping.getParams().get("syncMode");
        if (!"ASYNC".equalsIgnoreCase(mode)) {
            return SYNC_WRITE_EXECUTOR;
        }
        int batch = mapping.getBatchNum() > 0 ? mapping.getBatchNum() : 2;
        int n = Math.max(2, Math.min(4, batch));
        final String tag = "async-writer-" + mapping.getId() + "-" + ASYNC_POOL_SEQ.incrementAndGet();
        return Executors.newFixedThreadPool(n, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, tag);
                t.setDaemon(true);
                return t;
            }
        });
    }

    @Resource
    private ParserComponent parserComponent;

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private LogService logService;

    private final Map<String, Task> map = new ConcurrentHashMap<>();

    @Override
    public void start(Mapping mapping) {
        Thread worker = new Thread(() -> runSync(mapping, true));
        worker.setName("full-worker-" + mapping.getId());
        worker.setDaemon(false);
        worker.start();
    }

    /**
     * 同步执行全量任务
     *
     * @param mapping       驱动
     * @param publishClosed 完成后是否发布关闭事件
     */
    public void runSync(Mapping mapping, boolean publishClosed) {
        Assert.isTrue(tableGroupProfile.getTableGroupCount(mapping.getId()) > 0, "映射关系不能为空");
        final String metaId = mapping.getMetaId();
        try {
            Task task = map.computeIfAbsent(metaId, k -> new Task(metaId));
            logger.info("开始全量同步：{}, {}", metaId, mapping.getName());
            doTask(task, mapping);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            logService.log(LogType.SystemLog.ERROR, e.getMessage());
        } finally {
            map.remove(metaId);
            if (publishClosed) {
                publishClosedEvent(metaId);
            }
            logger.info("结束全量同步：{}, {}", metaId, mapping.getName());
        }
    }

    @Override
    public void close(String metaId) {
        map.computeIfPresent(metaId, (k, task) -> {
            task.stop();
            return null;
        });
    }

    @Override
    public void onApplicationEvent(FullRefreshEvent event) {
        flush(event.getTask());
    }

    private void doTask(Task task, Mapping mapping) {
        long now = Instant.now().toEpochMilli();
        task.setBeginTime(now);
        task.setEndTime(now);

        Meta meta = metaProfile.getMeta(task.getId());
        Map<String, String> snapshot = meta.getSnapshot();
        // 旧版单游标断点：绝对表序下标（用于无 tableProgress 时跳过已完成表）
        int legacyTableGroupIndex = NumberUtil.toInt(snapshot.get(ParserEnum.TABLE_GROUP_INDEX.getCode()),
                ParserEnum.TABLE_GROUP_INDEX.getDefaultValue());
        int legacyPageIndex = NumberUtil.toInt(snapshot.get(ParserEnum.PAGE_INDEX.getCode()),
                ParserEnum.PAGE_INDEX.getDefaultValue());
        String legacyCursor = snapshot.get(ParserEnum.CURSOR.getCode());
        boolean useLegacyBreakpoint = FullTableProgressUtil.isEmpty(snapshot)
                && (legacyTableGroupIndex > ParserEnum.TABLE_GROUP_INDEX.getDefaultValue()
                || legacyPageIndex > ParserEnum.PAGE_INDEX.getDefaultValue()
                || StringUtil.isNotBlank(legacyCursor));

        flush(task);

        int pageSize = ConfigConstant.PAGE_SIZE;
        int pageNum = 1;
        // Wave30：任一表失败时保留全量进度，禁止清空断点（否则缺表数据且无法续传）
        boolean allTablesOk = true;
        while (task.isRunning()) {
            Paging<TableGroup> paging = tableGroupProfile.queryTableGroup(mapping.getId(), null, pageNum, pageSize);
            if (paging == null || CollectionUtils.isEmpty(paging.getData())) {
                break;
            }
            List<TableGroup> page = new ArrayList<>(paging.getData());
            int pageStartIndex = (pageNum - 1) * pageSize;
            List<TableWork> works = new ArrayList<>(page.size());
            for (int j = 0; j < page.size(); j++) {
                works.add(new TableWork(pageStartIndex + j, page.get(j)));
            }

            int threadNum = Math.max(1, mapping.getThreadNum());
            // Wave30：旧实现 executeBySlice 建一个池只提交 1 个外层任务，外层又把 N 张表
            // 提交回同一个池并阻塞等待。threadNum=1 时唯一线程被外层占死 → 必然死锁；
            // threadNum>1 时也白白少一个工作线程。改为单池直接并发 N 张表，不嵌套提交。
            ExecutorService tablePool = Executors.newFixedThreadPool(Math.min(threadNum, works.size()));
            try {
                BatchTaskUtil.executeWithAwait(works, tablePool, work ->
                        syncOneTable(task, mapping, work, useLegacyBreakpoint, legacyTableGroupIndex,
                                legacyPageIndex, legacyCursor), logger);
            } catch (Exception e) {
                allTablesOk = false;
                logger.error("全量同步批次存在失败表，保留断点待重跑: {}", e.getMessage(), e);
                logService.log(LogType.SystemLog.ERROR, "全量同步批次失败，断点已保留: " + e.getMessage());
            } finally {
                tablePool.shutdownNow();
            }

            if (!task.isRunning() || page.size() < pageSize) {
                break;
            }
            pageNum++;
        }

        task.setEndTime(Instant.now().toEpochMilli());
        task.setTableGroupIndex(ParserEnum.TABLE_GROUP_INDEX.getDefaultValue());
        task.setPageIndex(ParserEnum.PAGE_INDEX.getDefaultValue());
        task.setCursors(null);
        if (task.isRunning() && allTablesOk) {
            clearFullProgress(task);
        } else {
            flush(task);
        }
    }

    private void syncOneTable(Task parent, Mapping mapping, TableWork work, boolean useLegacyBreakpoint,
                              int legacyTableGroupIndex, int legacyPageIndex, String legacyCursor) {
        if (!parent.isRunning()) {
            return;
        }
        TableGroup tableGroup = work.tableGroup;
        String tableGroupId = tableGroup.getId();
        int absoluteIndex = work.absoluteIndex;

        Meta meta = metaProfile.getMeta(parent.getId());
        Map<String, String> snapshot = meta.getSnapshot();
        if (FullTableProgressUtil.isDone(snapshot, tableGroupId)) {
            return;
        }
        // 旧断点：绝对下标之前的表视为已完成并跳过
        if (useLegacyBreakpoint && absoluteIndex < legacyTableGroupIndex) {
            markTableDone(parent, tableGroupId);
            return;
        }

        Task tableTask = parent.createTableTask(tableGroupId);
        TableSyncProgress progress = FullTableProgressUtil.getOrInit(snapshot, tableGroupId);
        if (useLegacyBreakpoint && absoluteIndex == legacyTableGroupIndex
                && progress.getPageIndex() == ParserEnum.PAGE_INDEX.getDefaultValue()
                && StringUtil.isBlank(progress.getCursor())) {
            tableTask.setPageIndex(legacyPageIndex);
            tableTask.setCursors(PrimaryKeyUtil.getLastCursors(legacyCursor));
        } else {
            tableTask.setPageIndex(progress.getPageIndex() > 0 ? progress.getPageIndex() : ParserEnum.PAGE_INDEX.getDefaultValue());
            tableTask.setCursors(PrimaryKeyUtil.getLastCursors(progress.getCursor()));
        }

        Executor writeExecutor = getWriteExecutor(mapping);
        try {
            parserComponent.execute(tableTask, mapping, tableGroup, writeExecutor);
            if (parent.isRunning()) {
                markTableDone(parent, tableGroupId);
            }
        } catch (Exception e) {
            logger.error("全量同步表失败: {} -> {}, {}",
                    tableGroup.getSourceTable() != null ? tableGroup.getSourceTable().getName() : tableGroupId,
                    tableGroup.getTargetTable() != null ? tableGroup.getTargetTable().getName() : tableGroupId,
                    e.getMessage(), e);
            logService.log(LogType.SystemLog.ERROR, e.getMessage());
            // Wave30：失败表必须让批次感知，否则会被当作成功并清空断点
            throw new IllegalStateException("全量同步表失败: " + e.getMessage(), e);
        } finally {
            if (writeExecutor instanceof ExecutorService) {
                ((ExecutorService) writeExecutor).shutdown();
            }
        }
    }

    private void flush(Task task) {
        synchronized (metaLock(task.getId())) {
            Meta meta = metaProfile.getMeta(task.getId());
            Assert.notNull(meta, "检查meta为空.");
            refreshMetaTotals(meta, task);

            Task root = task.getParent() != null ? task.getParent() : task;
            meta.setBeginTime(root.getBeginTime());
            meta.setEndTime(root.getEndTime());
            meta.setUpdateTime(Instant.now().toEpochMilli());
            Map<String, String> snapshot = meta.getSnapshot();

            if (StringUtil.isNotBlank(task.getTableGroupId())) {
                TableSyncProgress progress = new TableSyncProgress();
                progress.setPageIndex(task.getPageIndex());
                progress.setCursor(StringUtil.getIfBlank(PrimaryKeyUtil.encodeCursors(task.getCursors()), StringUtil.EMPTY));
                progress.setDone(false);
                FullTableProgressUtil.put(snapshot, task.getTableGroupId(), progress);
                snapshot.put(ParserEnum.PAGE_INDEX.getCode(), String.valueOf(task.getPageIndex()));
                snapshot.put(ParserEnum.CURSOR.getCode(), progress.getCursor());
            } else {
                snapshot.put(ParserEnum.PAGE_INDEX.getCode(), String.valueOf(task.getPageIndex()));
                snapshot.put(ParserEnum.CURSOR.getCode(),
                        StringUtil.getIfBlank(PrimaryKeyUtil.encodeCursors(task.getCursors()), StringUtil.EMPTY));
                snapshot.put(ParserEnum.TABLE_GROUP_INDEX.getCode(), String.valueOf(task.getTableGroupIndex()));
            }
            profileComponent.editConfigModel(meta);
        }
    }

    private void markTableDone(Task parent, String tableGroupId) {
        synchronized (metaLock(parent.getId())) {
            Meta meta = metaProfile.getMeta(parent.getId());
            Assert.notNull(meta, "检查meta为空.");
            TableSyncProgress progress = new TableSyncProgress();
            progress.setPageIndex(ParserEnum.PAGE_INDEX.getDefaultValue());
            progress.setCursor(StringUtil.EMPTY);
            progress.setDone(true);
            FullTableProgressUtil.put(meta.getSnapshot(), tableGroupId, progress);
            refreshMetaTotals(meta, parent);
            meta.setUpdateTime(Instant.now().toEpochMilli());
            profileComponent.editConfigModel(meta);
        }
    }

    private void clearFullProgress(Task task) {
        synchronized (metaLock(task.getId())) {
            Meta meta = metaProfile.getMeta(task.getId());
            Assert.notNull(meta, "检查meta为空.");
            refreshMetaTotals(meta, task);
            meta.setBeginTime(task.getBeginTime());
            meta.setEndTime(task.getEndTime());
            meta.setUpdateTime(Instant.now().toEpochMilli());
            Map<String, String> snapshot = meta.getSnapshot();
            FullTableProgressUtil.clear(snapshot);
            snapshot.put(ParserEnum.PAGE_INDEX.getCode(), String.valueOf(ParserEnum.PAGE_INDEX.getDefaultValue()));
            snapshot.put(ParserEnum.CURSOR.getCode(), StringUtil.EMPTY);
            snapshot.put(ParserEnum.TABLE_GROUP_INDEX.getCode(),
                    String.valueOf(ParserEnum.TABLE_GROUP_INDEX.getDefaultValue()));
            profileComponent.editConfigModel(meta);
        }
    }

    private void refreshMetaTotals(Meta meta, Task task) {
        long finished = meta.getSuccess().get() + meta.getFail().get();
        if (meta.getTotal().get() < finished) {
            meta.getTotal().set(finished);
        }
        Task root = task.getParent() != null ? task.getParent() : task;
        meta.setBeginTime(root.getBeginTime());
        meta.setEndTime(root.getEndTime());
    }

    private Object metaLock(String metaId) {
        return MetaLockUtil.lock(metaId);
    }

    /**
     * 带绝对下标的表任务（用于旧断点兼容）。
     */
    private static final class TableWork {
        private final int absoluteIndex;
        private final TableGroup tableGroup;

        private TableWork(int absoluteIndex, TableGroup tableGroup) {
            this.absoluteIndex = absoluteIndex;
            this.tableGroup = tableGroup;
        }
    }
}
