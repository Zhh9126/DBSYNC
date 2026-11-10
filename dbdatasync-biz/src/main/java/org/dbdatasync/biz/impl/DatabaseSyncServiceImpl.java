/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.impl;

import org.dbdatasync.biz.BizException;
import org.dbdatasync.biz.DatabaseSyncService;
import org.dbdatasync.biz.MappingService;
import org.dbdatasync.biz.precheck.PreCheckService;
import org.dbdatasync.biz.vo.DatabaseMappingVO;
import org.dbdatasync.biz.vo.DatabaseSyncTaskVO;
import org.dbdatasync.biz.vo.TablePreviewVO;
import org.dbdatasync.common.enums.CommonTaskTypeEnum;
import org.dbdatasync.common.enums.TaskLevelEnum;
import org.dbdatasync.common.model.ConfigModel;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.common.model.Result;
import org.dbdatasync.common.scheduled.ScheduledTaskJob;
import org.dbdatasync.common.scheduled.ScheduledTaskService;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.common.util.NumberUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.base.ConnectorFactory;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.ParserComponent;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.TaskProfile;
import org.dbdatasync.parser.model.Connector;
import org.dbdatasync.parser.model.FieldMapping;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.util.ConnectorInstanceUtil;
import org.dbdatasync.parser.util.ConnectorServiceContextUtil;
import org.dbdatasync.parser.util.DatabaseSyncMappingUtil;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.connector.ConnectorServiceContext;
import org.dbdatasync.sdk.connector.DefaultConnectorServiceContext;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.enums.ListenerTypeEnum;
import org.dbdatasync.sdk.enums.ModelEnum;
import org.dbdatasync.sdk.enums.TableTypeEnum;
import org.dbdatasync.sdk.listener.Listener;
import org.dbdatasync.sdk.model.CommonTaskSnapshot;
import org.dbdatasync.sdk.model.DatabaseMapping;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.MetaInfo;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.model.TableMapping;
import org.dbdatasync.sdk.spi.DatabaseSyncDetailService;
import org.dbdatasync.sdk.spi.TaskService;
import org.dbdatasync.sdk.util.DatabaseSyncProgressUtil;
import org.dbdatasync.sdk.util.MigrationStageUtil;
import org.dbdatasync.sdk.util.OffPeakWindowUtil;
import org.dbdatasync.sdk.util.TaskSnapshotUtil;
import org.dbdatasync.storage.impl.SnowflakeIdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.annotation.Resource;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 整库迁移业务服务：任务的增删改查/启停、库映射解析校验、表映射物化（table_group）、
 * 进度汇总（任务级 Meta + 表级明细快照）与「一键接续增量」。
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-05-22 00:00
 */
@Service
public class DatabaseSyncServiceImpl implements DatabaseSyncService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private TaskProfile taskProfile;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private ConnectorFactory connectorFactory;

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    private TaskService<DatabaseSyncTask> taskService;

    @Resource
    private DatabaseSyncDetailService databaseSyncDetailService;

    @Resource
    private ParserComponent parserComponent;

    @Resource
    private PreCheckService preCheckService;

    @Resource
    private MappingService mappingService;

    @Resource
    private org.dbdatasync.biz.ConnectorService connectorService;

    @Resource
    private ScheduledTaskService scheduledTaskService;

    @Override
    public DatabaseSyncTaskVO get(String id) {
        DatabaseSyncTask task = taskService.get(id);
        Assert.notNull(task, "任务不存在");
        return convertTask2Vo(task);
    }

    @Override
    public String add(Map<String, String> params) {
        String name = params.get("name");
        if (StringUtil.isBlank(name)) {
            throw new BizException("任务名称不能为空");
        }
        List<DatabaseMappingVO> mappings = parseDatabaseMappings(params.get("databaseMappingsJson"));
        checkDatabaseMapping(mappings);
        if (CollectionUtils.isEmpty(mappings)) {
            throw new BizException("请至少添加一组库映射");
        }
        normalizeAndSortMappings(mappings);
        validateMappingConnectors(mappings);
        validateTableMappings(mappings);
        DatabaseSyncTask task = new DatabaseSyncTask();
        fillTaskOnAdd(task, params);
        task.setDatabaseMappings(toPersistMappings(mappings));
        // 任务需先落库（含任务级 Meta）才能按 taskId 建连与写 table_group；失败则整单回滚
        String taskId = taskService.add(task);
        try {
            tableGroupProfile.addTableGroupBatch(buildTableGroups(taskId, mappings));
        } catch (Exception e) {
            try {
                taskService.delete(taskId);
            } catch (Exception cleanupEx) {
                logger.error("整库迁移任务创建失败后回滚删除失败: id={}", taskId, cleanupEx);
            }
            throw new BizException(e.getMessage(), e);
        }
        // 预建明细分表并对齐明细行，详情页 JOIN 查询不依赖任务是否已写出数据
        taskProfile.createRunDetailTable(taskId);
        databaseSyncDetailService.syncTaskTableMetaDetails(taskId);
        logger.info("整库迁移任务已保存: id={}, name={}, mappingCount={}", taskId, name, mappings.size());
        return taskId;
    }

    @Override
    public String edit(Map<String, String> params) {
        String id = params.get("id");
        Assert.hasText(id, "任务 ID 不能为空");
        DatabaseSyncTask task = taskService.get(id);
        Assert.notNull(task, "任务不存在");
        if (taskService.isRunning(id)) {
            throw new BizException("任务正在运行，请先停止");
        }
        List<DatabaseMappingVO> mappings = parseDatabaseMappings(params.get("databaseMappingsJson"));
        checkDatabaseMapping(mappings);
        if (CollectionUtils.isEmpty(mappings)) {
            throw new BizException("请至少添加一组库映射");
        }
        normalizeAndSortMappings(mappings);
        validateMappingConnectors(mappings);
        validateTableMappings(mappings);
        fillTaskOnEdit(task, params);
        task.setDatabaseMappings(toPersistMappings(mappings));
        // 表映射重建：失败回滚旧映射
        List<TableGroup> newGroups = buildTableGroups(id, mappings);
        List<TableGroup> oldGroups = new ArrayList<>();
        tableGroupProfile.pageScanTableGroups(id, 1000, oldGroups::addAll);
        try {
            tableGroupProfile.removeTableGroupsByTaskId(id);
            tableGroupProfile.addTableGroupBatch(newGroups);
        } catch (Exception e) {
            restoreTableGroups(id, oldGroups);
            throw new BizException(e.getMessage(), e);
        }
        taskProfile.clearRunData(id);
        taskProfile.resetRunProgress(id);
        String editedId = taskService.edit(task);
        databaseSyncDetailService.syncTaskTableMetaDetails(editedId);
        return editedId;
    }

    private void checkDatabaseMapping(List<DatabaseMappingVO> mappings) {
        for (DatabaseMappingVO mapping : mappings) {
            if (!StringUtil.equals(mapping.getSourceConnectorId(), mapping.getTargetConnectorId())) {
                continue;
            }
            boolean selectedDB = StringUtil.isNotBlank(mapping.getSourceDatabase()) && StringUtil.isNotBlank(mapping.getTargetDatabase());
            if (selectedDB && StringUtil.equals(mapping.getSourceDatabase(), mapping.getTargetDatabase())) {
                throw new BizException("同源同库不允许同步，请更换目标连接或数据库！");
            }
            boolean selectedSchema = StringUtil.isNotBlank(mapping.getSourceSchema()) && StringUtil.isNotBlank(mapping.getTargetSchema());
            if (selectedDB || !selectedSchema || !StringUtil.equals(mapping.getSourceSchema(), mapping.getTargetSchema())) {
                continue;
            }
            throw new BizException("同源同schema不允许同步，请更换目标连接或schema！");
        }
    }

    @Override
    public String delete(String id) {
        Assert.hasText(id, "任务 ID 不能为空");
        if (taskService.isRunning(id)) {
            throw new BizException("任务正在运行，请先停止");
        }
        taskService.delete(id);
        return "删除成功";
    }

    @Override
    public String start(String id) {
        Assert.hasText(id, "任务 ID 不能为空");
        DatabaseSyncTask task = taskService.get(id);
        Assert.notNull(task, "任务不存在");
        if (CollectionUtils.isEmpty(task.getDatabaseMappings())) {
            throw new BizException("任务未配置库映射，无法启动");
        }
        if (tableGroupProfile.getTableGroupCount(id) <= 0) {
            throw new BizException("任务未配置库表映射，无法启动");
        }
        preCheckService.assertPassable("database-sync", id);
        // Wave11 低峰时间窗：开关开启 + 时间窗已配置 + 当前不在窗口内 → 预约一次性启动；
        // 判定/注册全程 try-catch，调度器不可用或注册失败一律降级为立即启动，绝不阻断既有启动流程。
        if (task.isEnableOffPeakWindow()
                && OffPeakWindowUtil.isConfigured(task.getWindowStart(), task.getWindowEnd())) {
            try {
                LocalTime now = LocalTime.now();
                if (!OffPeakWindowUtil.isWithin(now, task.getWindowStart(), task.getWindowEnd())) {
                    String cron = OffPeakWindowUtil.toCron(task.getWindowStart());
                    if (cron != null) {
                        // key 带 UUID，规避 ScheduledTaskServiceImpl 对同 key 抛「任务已启动」
                        String key = "dbSyncWindow-" + id + "-" + UUID.randomUUID();
                        ScheduledTaskJob job = () -> {
                            try {
                                taskService.start(id);
                            } finally {
                                scheduledTaskService.stop(key);
                            }
                        };
                        scheduledTaskService.start(key, cron, job);
                        return "已预约至低峰窗口 " + task.getWindowStart() + " 启动（服务重启后预约失效，请重新触发）";
                    }
                }
            } catch (Exception e) {
                logger.warn("整库迁移任务 [{}] 低峰窗口预约失败，降级为立即启动: {}", id, e.getMessage(), e);
            }
        }
        taskService.start(id);
        return "启动成功";
    }

    @Override
    public String stop(String id) {
        Assert.hasText(id, "任务 ID 不能为空");
        taskService.stop(id);
        return "停止成功";
    }

    /** 迁移编排阶段：推进逻辑与阶段链见 {@link org.dbdatasync.sdk.util.MigrationStageUtil}（snapshot 键同其 SNAPSHOT_KEY_STAGE） */
    private static final String SNAPSHOT_KEY_STAGE = MigrationStageUtil.SNAPSHOT_KEY_STAGE;

    @Override
    public String currentMigrationStage(String id) {
        Assert.hasText(id, "任务 ID 不能为空");
        DatabaseSyncTask task = taskService.get(id);
        if (task == null) {
            throw new BizException("任务不存在");
        }
        Meta taskMeta = metaProfile.getMetaByTaskId(task.getId(), TaskLevelEnum.TASK);
        if (taskMeta != null && taskMeta.getSnapshot() != null) {
            String stage = taskMeta.getSnapshot().get(SNAPSHOT_KEY_STAGE);
            if (StringUtil.isNotBlank(stage)) {
                return MigrationStageUtil.normalize(stage);
            }
        }
        return MigrationStageUtil.normalize(task.getMigrationStage());
    }

    @Override
    public String advanceMigrationStage(String id) {
        Assert.hasText(id, "任务 ID 不能为空");
        DatabaseSyncTask task = taskService.get(id);
        if (task == null) {
            throw new BizException("任务不存在");
        }
        String current = currentMigrationStage(id);
        String next = MigrationStageUtil.advance(current);
        // 持久化到任务级 Meta.SNAPSHOT；无 meta 时回落任务配置
        Meta taskMeta = metaProfile.getMetaByTaskId(task.getId(), TaskLevelEnum.TASK);
        if (taskMeta != null) {
            if (taskMeta.getSnapshot() == null) {
                taskMeta.setSnapshot(new java.util.concurrent.ConcurrentHashMap<>());
            }
            taskMeta.getSnapshot().put(SNAPSHOT_KEY_STAGE, next);
            metaProfile.updateMeta(taskMeta);
        } else {
            task.setMigrationStage(next);
            taskService.edit(task);
        }
        logger.info("整库迁移任务 [{}] 编排阶段推进: {} -> {}", id, current, next);
        return next;
    }

    @Override
    public Paging<DatabaseSyncTaskVO> search(Map<String, String> params) {
        Paging paging = taskService.search(params, CommonTaskTypeEnum.DATABASE_SYNC);
        Collection data = paging.getData();
        if (CollectionUtils.isEmpty(data)) {
            return paging;
        }
        List<DatabaseSyncTaskVO> list = new ArrayList<>();
        data.forEach(item -> {
            if (item instanceof DatabaseSyncTask) {
                DatabaseSyncTask task = (DatabaseSyncTask) item;
                DatabaseSyncTaskVO vo = convertTask2Vo(task);
                if (vo != null) {
                    int tableCount = tableGroupProfile.getTableGroupCount(task.getId());
                    Meta taskMeta = metaProfile.getMetaByTaskId(task.getId(), TaskLevelEnum.TASK);
                    boolean roundDone = taskMeta != null && DatabaseSyncProgressUtil.isRoundDone(taskMeta.getState());
                    Map mappingStatus = DatabaseSyncProgressUtil.readMappingStatus(taskMeta == null ? null : taskMeta.getSnapshot());
                    List<CommonTaskSnapshot> tableSnapshots = collectTableSnapshots(task.getId());
                    vo.setProgress(DatabaseSyncProgressUtil.calculateProgressPercent(task, tableCount, vo.getMappingCount(), roundDone, mappingStatus, tableSnapshots));
                    vo.setTotalTableCount(tableCount);
                    vo.setCompletedTableCount(DatabaseSyncProgressUtil.countCompletedTables(task, tableCount, roundDone, mappingStatus, tableSnapshots));
                    vo.setErrorCount(0L);
                    if (taskMeta != null) {
                        if (taskMeta.getFail() != null) {
                            vo.setErrorCount(taskMeta.getFail().get());
                        }
                        vo.setMetaState(taskMeta.getState());
                        vo.setBeginTime(taskMeta.getBeginTime() > 0 ? taskMeta.getBeginTime() : null);
                        vo.setEndTime(taskMeta.getEndTime() > 0 ? taskMeta.getEndTime() : null);
                    }
                    // 迁移编排阶段：优先取 Meta.SNAPSHOT（推进后持久化），否则任务配置默认
                    if (taskMeta != null && taskMeta.getSnapshot() != null) {
                        String stage = taskMeta.getSnapshot().get(SNAPSHOT_KEY_STAGE);
                        if (StringUtil.isNotBlank(stage)) {
                            vo.setMigrationStage(stage);
                        }
                    }
                    list.add(vo);
                }
            }
        });
        paging.setData(list);
        return paging;
    }

    @Override
    public List<DatabaseSyncTaskVO> getAll() {
        return taskService.getTaskAll(CommonTaskTypeEnum.DATABASE_SYNC)
                .stream()
                .filter(DatabaseSyncTask.class::isInstance)
                .map(t -> convertTask2Vo((DatabaseSyncTask) t))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public Paging searchResult(Map<String, String> params) {
        return databaseSyncDetailService.result(params);
    }

    @Override
    public TablePreviewVO previewTables(Map<String, String> params) {
        String connectorId = params.get("connectorId");
        String database = params.get("database");
        String schema = params.get("schema");
        String searchKey = StringUtil.trimToEmpty(params.get("searchKey"));
        int offset = Math.max(0, NumberUtil.toInt(params.get("offset"), 0));
        int limit = NumberUtil.toInt(params.get("limit"), 0);
        if (limit <= 0) {
            throw new BizException("limit 必须大于 0");
        }
        if (StringUtil.isBlank(connectorId)) {
            throw new BizException("连接器不能为空");
        }
        Connector connector = profileComponent.getConnector(connectorId);
        if (connector == null) {
            throw new BizException("连接器不存在");
        }
        // 实例ID融合 database/schema，避免不同库的预览复用同一连接上下文
        DefaultConnectorServiceContext context = ConnectorServiceContextUtil.buildConnectorServiceContext(
                "database-sync-preview", connectorId, database, schema, connectorId, database, schema, true);
        String previewInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(
                "database-sync-preview", connectorId, database, schema, "S");
        try {
            connectorFactory.connect(previewInstanceId, connector.getConfig(), database, schema);
            ConnectorInstance connectorInstance = connectorFactory.connect(previewInstanceId);
            List<Table> tables = connectorFactory.getTables(connectorInstance, (ConnectorServiceContext) context);
            if (CollectionUtils.isEmpty(tables)) {
                return TablePreviewVO.of(Collections.emptyList(), 0L, String.valueOf(offset), limit);
            }
            if (StringUtil.isNotBlank(searchKey)) {
                String key = searchKey.toUpperCase();
                tables = tables.stream().filter(t -> t.getName() != null && t.getName().toUpperCase().contains(key)).collect(Collectors.toList());
            }
            Set<String> excludeTables = parseExcludeTables(params.get("excludeTablesJson"));
            if (!CollectionUtils.isEmpty(excludeTables)) {
                tables = tables.stream().filter(t -> t.getName() == null || !excludeTables.contains(t.getName())).collect(Collectors.toList());
            }
            tables.sort(Comparator.comparing(Table::getName, String.CASE_INSENSITIVE_ORDER));
            int realTotal = tables.size();
            Map<String, Integer> typeCounts = new HashMap<>(4);
            for (Table table : tables) {
                String type = table.getType() != null ? table.getType() : TableTypeEnum.TABLE.getCode();
                typeCounts.merge(type.toUpperCase(), 1, Integer::sum);
            }
            List<Table> pageRows = tables.stream().skip(offset).limit(limit).collect(Collectors.toList());
            TablePreviewVO result = TablePreviewVO.of(pageRows, realTotal, String.valueOf(offset), limit);
            result.setTypeCounts(typeCounts);
            return result;
        } finally {
            connectorFactory.disconnect(previewInstanceId);
        }
    }

    /**
     * 解析库映射 JSON（Wave10 P0-2 兼容归一化层）。
     *
     * <p>统一契约：{@code databaseMappingsJson} 为数组，每元素 {@code tableMappings} 为数组。
     * ① blank→emptyList（保留既有 {@code StringUtil.isBlank} 保护）；② 根对象→包装为单元素数组；
     * ③ 根字符串→先试 {@code JSON.parseArray}，失败降级 emptyList；
     * ④ 逐元素把 {@code tableMappings} 的 null/空白字符串/对象/字符串化数组归一为 {@code []} 或合法数组；
     * ⑤ 剔除 null 元素；⑥ 任何解析异常统一包装为可读 {@link BizException}，
     * <strong>禁止把 fastjson2 的 {@code e.getMessage()}（syntax error : { / offset N）透传前端</strong>。</p>
     *
     * <p>归一后继续走既有 {@code normalizeAndSortMappings()} → {@code getSortedTableMappings()}
     * → {@code buildTableGroups()}，顺序与配置一致；{@code add()/edit()} 主流程零改动。</p>
     */
    private List<DatabaseMappingVO> parseDatabaseMappings(String mappingsJson) {
        if (StringUtil.isBlank(mappingsJson)) {
            return Collections.emptyList();
        }
        try {
            String normalized = normalizeMappingsJson(mappingsJson);
            List<DatabaseMappingVO> parsed = JsonUtil.jsonToArray(normalized, DatabaseMappingVO.class);
            if (parsed == null) {
                return Collections.emptyList();
            }
            List<DatabaseMappingVO> mappings = new ArrayList<>(parsed);
            normalizeMappings(mappings);
            return mappings;
        } catch (RuntimeException e) {
            // 含 com.alibaba.fastjson2.JSONException（syntax error : { / offset N 等），统一包装为业务异常
            logger.warn("databaseMappingsJson 归一化失败，按格式错误提示用户: {}", e.getClass().getSimpleName());
            throw new BizException("库映射格式错误，请重新选择表映射后重试");
        }
    }

    /**
     * 把根形态与每个元素的 {@code tableMappings} 字段归一为合法数组 JSON 文本。
     *
     * @param json 原始隐藏域值
     * @return 可安全交给 {@code JsonUtil.jsonToArray(..., DatabaseMappingVO.class)} 的数组文本
     */
    private String normalizeMappingsJson(String json) {
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.isEmpty()) {
            return "[]";
        }
        String arrayJson;
        char first = trimmed.charAt(0);
        if (first == '[') {
            arrayJson = trimmed;
        } else if (first == '{') {
            // 根对象 → 包装为单元素数组（历史回显/编辑路径可能写入）
            arrayJson = "[" + trimmed + "]";
        } else {
            // 根为字符串（" " / 字符串化 JSON）或其它标量：先试 parseArray，失败降级空数组
            try {
                com.alibaba.fastjson2.JSONArray probe = com.alibaba.fastjson2.JSON.parseArray(trimmed);
                if (probe == null || probe.isEmpty()) {
                    return "[]";
                }
                for (int i = 0; i < probe.size(); i++) {
                    if (!(probe.get(i) instanceof com.alibaba.fastjson2.JSONObject)) {
                        return "[]";
                    }
                }
                arrayJson = trimmed;
            } catch (RuntimeException ignore) {
                return "[]";
            }
        }
        if ("[]".equals(arrayJson)) {
            return arrayJson;
        }
        // 畸形数组解析异常将冒泡到 parseDatabaseMappings，统一包装为可读 BizException
        com.alibaba.fastjson2.JSONArray array = com.alibaba.fastjson2.JSON.parseArray(arrayJson);
        if (array == null) {
            return "[]";
        }
        for (int i = 0; i < array.size(); i++) {
            Object element = array.get(i);
            if (!(element instanceof com.alibaba.fastjson2.JSONObject)) {
                continue;
            }
            com.alibaba.fastjson2.JSONObject obj = (com.alibaba.fastjson2.JSONObject) element;
            obj.put("tableMappings", normalizeRawTableMappings(obj.get("tableMappings")));
        }
        return com.alibaba.fastjson2.JSON.toJSONString(array);
    }

    /**
     * {@code tableMappings} 字段归一：数组原样、字符串化数组解析成数组，
     * 其余（null / 空白字符串 / 对象 / 标量）统一为 {@code []}。
     */
    private Object normalizeRawTableMappings(Object raw) {
        if (raw instanceof com.alibaba.fastjson2.JSONArray) {
            return raw;
        }
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.startsWith("[")) {
                try {
                    com.alibaba.fastjson2.JSONArray parsed = com.alibaba.fastjson2.JSON.parseArray(s);
                    return parsed == null ? new com.alibaba.fastjson2.JSONArray() : parsed;
                } catch (RuntimeException ignore) {
                    return new com.alibaba.fastjson2.JSONArray();
                }
            }
        }
        return new com.alibaba.fastjson2.JSONArray();
    }

    /** 逐元素归一表映射（VO 层兜底）并剔除 null 元素。 */
    private void normalizeMappings(List<DatabaseMappingVO> mappings) {
        if (CollectionUtils.isEmpty(mappings)) {
            return;
        }
        java.util.Iterator<DatabaseMappingVO> iterator = mappings.iterator();
        while (iterator.hasNext()) {
            DatabaseMappingVO mapping = iterator.next();
            if (mapping == null) {
                iterator.remove();
                continue;
            }
            mapping.normalizeTableMappings();
        }
    }

    private List<DatabaseMapping> toPersistMappings(List<DatabaseMappingVO> mappings) {
        if (CollectionUtils.isEmpty(mappings)) {
            return new ArrayList<>();
        }
        List<DatabaseMapping> result = new ArrayList<>(mappings.size());
        for (DatabaseMappingVO mapping : mappings) {
            if (mapping == null) {
                continue;
            }
            result.add(mapping.toDatabaseMapping());
        }
        return result;
    }

    private Set<String> parseExcludeTables(String excludeTablesJson) {
        if (StringUtil.isBlank(excludeTablesJson)) {
            return Collections.emptySet();
        }
        List<String> names = JsonUtil.jsonToArray(excludeTablesJson, String.class);
        if (CollectionUtils.isEmpty(names)) {
            return Collections.emptySet();
        }
        Set<String> exclude = new HashSet<>();
        for (String name : names) {
            if (StringUtil.isNotBlank(name)) {
                exclude.add(name);
            }
        }
        return exclude;
    }

    private void normalizeAndSortMappings(List<DatabaseMappingVO> mappings) {
        if (CollectionUtils.isEmpty(mappings)) {
            return;
        }
        for (int i = 0; i < mappings.size(); i++) {
            DatabaseMappingVO mapping = mappings.get(i);
            if (mapping.getIndex() <= 0) {
                mapping.setIndex(i + 1);
            }
            List<TableMapping> tableMappings = mapping.getTableMappings();
            if (CollectionUtils.isEmpty(tableMappings)) {
                continue;
            }
            for (int j = 0; j < tableMappings.size(); j++) {
                TableMapping row = tableMappings.get(j);
                if (row.getIndex() <= 0) {
                    row.setIndex(j + 1);
                }
            }
            tableMappings.sort(Comparator.comparingInt(TableMapping::getIndex));
            for (int j = 0; j < tableMappings.size(); j++) {
                tableMappings.get(j).setIndex(j + 1);
            }
        }
        mappings.sort(Comparator.comparingInt(DatabaseMapping::getIndex));
        for (int i = 0; i < mappings.size(); i++) {
            mappings.get(i).setIndex(i + 1);
        }
    }

    private void fillTaskOnAdd(DatabaseSyncTask task, Map<String, String> params) {
        if (StringUtil.isBlank(task.getId())) {
            task.setId(String.valueOf(snowflakeIdWorker.nextId()));
            task.setType(CommonTaskTypeEnum.DATABASE_SYNC.name());
        }
        long now = Instant.now().toEpochMilli();
        task.setCreateTime(task.getCreateTime() == null ? now : task.getCreateTime());
        task.setUpdateTime(now);
        task.setName(params.get("name"));
        fillSyncStrategy(task, params);
    }

    private void fillTaskOnEdit(DatabaseSyncTask task, Map<String, String> params) {
        String name = params.get("name");
        if (StringUtil.isBlank(name)) {
            throw new BizException("任务名称不能为空");
        }
        task.setName(name);
        task.setUpdateTime(Instant.now().toEpochMilli());
        fillSyncStrategy(task, params);
    }

    /**
     * 同步策略开关：结构迁移（含覆盖）、数据迁移（含覆盖）、新表自动发现；
     * 覆盖开关仅在对应能力开启时生效。
     */
    private void fillSyncStrategy(DatabaseSyncTask task, Map<String, String> params) {
        task.setEnableCopySchema(StringUtil.isNotBlank(params.get("enableCopySchema")));
        task.setEnableCopyData(StringUtil.isNotBlank(params.get("enableCopyData")));
        task.setOverwriteSchema(task.isEnableCopySchema() && StringUtil.isNotBlank(params.get("overwriteSchema")));
        task.setOverwriteData(task.isEnableCopyData() && StringUtil.isNotBlank(params.get("overwriteData")));
        task.setEnableAutoDiscover(StringUtil.isNotBlank(params.get("enableAutoDiscover")));
        // Wave30：大数据量性能参数从页面写入并夹紧。此前三项只有模型默认值、页面无输入、
        // 引擎也只读取默认值，调优建议形同虚设；缺字段时保留任务原值，兼容存量 task.JSON。
        task.setReadNum(clampInt(params.get("readNum"), task.getReadNum(), 100, 100000));
        task.setBatchNum(clampInt(params.get("batchNum"), task.getBatchNum(), 1, 10000));
        task.setThreadNum(clampInt(params.get("threadNum"), task.getThreadNum(), 1, 32));
        // Wave11：限速 / 低峰时间窗 / 自动推进阶段（默认 0 / false / ""，存量任务缺字段回落默认值）
        task.setRecordsPerSecond(parseRate(params.get("recordsPerSecond")));
        task.setBytesPerSecond(parseRate(params.get("bytesPerSecond")));
        task.setEnableOffPeakWindow(StringUtil.isNotBlank(params.get("enableOffPeakWindow")));
        String windowStart = OffPeakWindowUtil.normalizeTime(params.get("windowStart"));
        String windowEnd = OffPeakWindowUtil.normalizeTime(params.get("windowEnd"));
        if (task.isEnableOffPeakWindow() && !OffPeakWindowUtil.isConfigured(windowStart, windowEnd)) {
            throw new BizException("低峰时间窗格式错误，请使用 HH:mm（24 小时制）");
        }
        task.setWindowStart(windowStart);
        task.setWindowEnd(windowEnd);
        task.setAutoAdvanceStage(StringUtil.isNotBlank(params.get("autoAdvanceStage")));
    }

    /** Wave30：性能参数解析并夹紧到安全区间；空白 / 异常 / 越界一律回退原值或默认值。 */
    private int clampInt(String raw, int current, int min, int max) {
        int fallback = current > 0 ? current : min;
        try {
            int value = NumberUtil.toInt(StringUtil.trimToEmpty(raw), fallback);
            if (value < min || value > max) {
                return fallback >= min && fallback <= max ? fallback : min;
            }
            return value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 限速解析：异常 / 空白 / &lt;=0 一律归一为 0（0 = 不限速），绝不抛异常。 */
    private long parseRate(String raw) {
        try {
            long value = NumberUtil.toLong(StringUtil.trimToEmpty(raw), 0L);
            return value > 0 ? value : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private void restoreTableGroups(String taskId, List<TableGroup> oldGroups) {
        try {
            tableGroupProfile.removeTableGroupsByTaskId(taskId);
        } catch (Exception cleanupEx) {
            logger.error("编辑失败回滚：清理残留 table_group 失败: taskId={}", taskId, cleanupEx);
        }
        if (CollectionUtils.isEmpty(oldGroups)) {
            return;
        }
        try {
            tableGroupProfile.addTableGroupBatch(oldGroups);
        } catch (Exception restoreEx) {
            logger.error("编辑失败回滚：恢复旧 table_group 失败: taskId={}", taskId, restoreEx);
        }
    }

    private void validateMappingConnectors(List<? extends DatabaseMapping> mappings) {
        for (int i = 0; i < mappings.size(); i++) {
            DatabaseMapping mapping = mappings.get(i);
            if (StringUtil.isBlank(mapping.getSourceConnectorId())) {
                throw new BizException("库映射 " + (i + 1) + " 缺少源端连接器");
            }
            if (StringUtil.isBlank(mapping.getTargetConnectorId())) {
                throw new BizException("库映射 " + (i + 1) + " 缺少目标端连接器");
            }
        }
    }

    private void validateTableMappings(List<DatabaseMappingVO> mappings) {
        if (CollectionUtils.isEmpty(mappings)) {
            return;
        }
        for (int i = 0; i < mappings.size(); i++) {
            List<TableMapping> tableMappings = mappings.get(i).getTableMappings();
            if (CollectionUtils.isEmpty(tableMappings)) {
                continue;
            }
            for (TableMapping row : tableMappings) {
                if (row == null || !StringUtil.isBlank(row.getTargetTable())) {
                    continue;
                }
                String source = StringUtil.isNotBlank(row.getSourceTable()) ? row.getSourceTable() : "";
                throw new BizException(String.format("库映射 %d：源表「%s」的目标表名不能为空", i + 1, source));
            }
        }
    }

    private DatabaseSyncTaskVO convertTask2Vo(DatabaseSyncTask task) {
        if (task == null) {
            return null;
        }
        List<TableGroup> tableGroups = new ArrayList<>();
        tableGroupProfile.pageScanTableGroups(task.getId(), 1000, tableGroups::addAll);
        List<DatabaseMappingVO> mappingViews = buildDatabaseMappingVo(DatabaseSyncMappingUtil.sortByIndex(task.getDatabaseMappings()), tableGroups);
        DatabaseMappingVO first = CollectionUtils.isEmpty(mappingViews) ? null : mappingViews.get(0);
        Connector source = first == null ? null : profileComponent.getConnector(first.getSourceConnectorId());
        Connector target = first == null ? null : profileComponent.getConnector(first.getTargetConnectorId());
        DatabaseSyncTaskVO vo = new DatabaseSyncTaskVO(source, target);
        BeanUtils.copyProperties(task, vo);
        vo.setMappingViews(mappingViews);
        vo.setMappingCount(CollectionUtils.isEmpty(mappingViews) ? 0 : mappingViews.size());
        return vo;
    }

    /**
     * 汇总各表级明细 Meta 快照（与 table_group id 顺序对齐，供进度计算）。
     */
    private List<CommonTaskSnapshot> collectTableSnapshots(String taskId) {
        List<String> ids = tableGroupProfile.listTableGroupIds(taskId);
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        Map<String, Meta> metaMap = metaProfile.getDetailMetaMap(ids);
        List<CommonTaskSnapshot> snapshots = new ArrayList<>(ids.size());
        for (String groupId : ids) {
            if (StringUtil.isBlank(groupId)) {
                snapshots.add(null);
                continue;
            }
            Meta meta = metaMap == null ? null : metaMap.get(groupId);
            snapshots.add(meta == null ? null : TaskSnapshotUtil.readTableSnapshot(meta.getSnapshot()));
        }
        return snapshots;
    }

    private List<DatabaseMappingVO> buildDatabaseMappingVo(List<DatabaseMapping> mappings, List<TableGroup> tableGroups) {
        if (CollectionUtils.isEmpty(mappings)) {
            return new ArrayList<>();
        }
        Map<String, List<TableGroup>> groupsByKey = new HashMap<>();
        if (!CollectionUtils.isEmpty(tableGroups)) {
            for (TableGroup group : tableGroups) {
                if (group == null) {
                    continue;
                }
                groupsByKey.computeIfAbsent(group.buildDatabaseMappingKey(), k -> new ArrayList<>()).add(group);
            }
        }
        List<DatabaseMappingVO> result = new ArrayList<>(mappings.size());
        for (DatabaseMapping src : mappings) {
            if (src == null) {
                continue;
            }
            DatabaseMappingVO vo = DatabaseMappingVO.from(src);
            List<TableMapping> tableMappings = new ArrayList<>();
            List<TableGroup> groups = groupsByKey.getOrDefault(src.buildDatabaseMappingKey(), Collections.emptyList());
            groups.stream().sorted(Comparator.comparingInt(TableGroup::getIndex)).forEach(group -> {
                Table sourceTable = group.getSourceTable();
                Table targetTable = group.getTargetTable();
                if (sourceTable != null && targetTable != null) {
                    tableMappings.add(DatabaseSyncMappingUtil.toTableMapping(sourceTable.getName(), targetTable.getName(), group.getIndex()));
                }
            });
            vo.setTableMappings(tableMappings);
            result.add(vo);
        }
        return result;
    }

    /**
     * 按映射对物化 TableGroup：源表回读元数据（列清单），目标表先以表名占位
     * （元数据由迁移执行时回读/自动建表补齐）。
     */
    private List<TableGroup> buildTableGroups(String taskId, List<DatabaseMappingVO> mappings) {
        if (StringUtil.isBlank(taskId) || CollectionUtils.isEmpty(mappings)) {
            return Collections.emptyList();
        }
        List<TableGroup> groups = new ArrayList<>();
        int sortIndex = 0;
        long now = Instant.now().toEpochMilli();
        for (DatabaseMappingVO mapping : mappings) {
            List<TableMapping> tableMappings = mapping.getSortedTableMappings();
            if (CollectionUtils.isEmpty(tableMappings)) {
                continue;
            }
            Connector sourceConnector = profileComponent.getConnector(mapping.getSourceConnectorId());
            Assert.notNull(sourceConnector, "源连接器不存在");
            Assert.notNull(sourceConnector.getConfig(), "源连接器配置不存在");
            // 与 ParserComponentImpl#getMetaInfo 的实例ID保持一致（mappingId + connectorId + suffix）
            connectorFactory.connect(
                    ConnectorInstanceUtil.buildConnectorInstanceId(taskId, mapping.getSourceConnectorId(), "S"),
                    sourceConnector.getConfig(), mapping.getSourceDatabase(), mapping.getSourceSchema());
            List<String> sourceNames = tableMappings.stream().map(TableMapping::getSourceTable).collect(Collectors.toList());
            Map<String, Table> sourceMetaMap = loadMetaTableMap(taskId, mapping, sourceNames);
            for (TableMapping tableMapping : tableMappings) {
                Table sourceTable = sourceMetaMap.get(tableMapping.getSourceTable());
                if (sourceTable == null) {
                    throw new BizException(String.format("源表 [%s] 不存在或无法读取元数据", tableMapping.getSourceTable()));
                }
                TableGroup group = new TableGroup();
                group.setId(String.valueOf(snowflakeIdWorker.nextId()));
                group.setTaskId(taskId);
                group.setIndex(++sortIndex);
                group.setSourceConnectorId(mapping.getSourceConnectorId());
                group.setTargetConnectorId(mapping.getTargetConnectorId());
                group.setSourceDatabase(StringUtil.getIfBlank(mapping.getSourceDatabase(), ""));
                group.setTargetDatabase(StringUtil.getIfBlank(mapping.getTargetDatabase(), ""));
                group.setSourceSchema(StringUtil.getIfBlank(mapping.getSourceSchema(), ""));
                group.setTargetSchema(StringUtil.getIfBlank(mapping.getTargetSchema(), ""));
                group.setSourceTable(sourceTable);
                Table targetTable = new Table();
                targetTable.setName(tableMapping.getTargetTable());
                targetTable.setType(TableTypeEnum.TABLE.getCode());
                group.setTargetTable(targetTable);
                group.setCreateTime(now);
                group.setUpdateTime(now);
                groups.add(group);
            }
        }
        return groups;
    }

    public Map<String, Table> loadMetaTableMap(String taskId, DatabaseMappingVO ctx, List<String> tableNames) {
        DefaultConnectorServiceContext context = ConnectorServiceContextUtil.buildConnectorServiceContext(
                taskId,
                ctx.getSourceConnectorId(),
                ctx.getSourceDatabase(),
                ctx.getSourceSchema(),
                ctx.getTargetConnectorId(),
                ctx.getTargetDatabase(),
                ctx.getTargetSchema(),
                true);
        tableNames.stream().distinct().forEach(context::addTablePattern);
        List<MetaInfo> metaInfos = parserComponent.getMetaInfo(context);
        Map<String, Table> tableMap = new HashMap<>(metaInfos.size());
        for (MetaInfo metaInfo : metaInfos) {
            if (metaInfo == null || StringUtil.isBlank(metaInfo.getTable())) {
                continue;
            }
            Table table = new Table();
            table.setName(metaInfo.getTable());
            table.setType(metaInfo.getTableType());
            table.setColumn(metaInfo.getColumn());
            // key 小写归一，与调用方表名匹配（Oracle-族回读为实际大写名）
            tableMap.put(metaInfo.getTable().toLowerCase(), table);
        }
        return tableMap;
    }

    /**
     * 内置常见数据库管理系统库名（产品级通用知识，跨环境一致），
     * 「实例级一键全库映射」按小写匹配排除，避免误同步元数据库。
     */
    private static final Set<String> SYSTEM_DATABASES = new HashSet<>(Arrays.asList(
            "information_schema", "mysql", "performance_schema", "sys", "innodb",
            "pg_catalog", "pg_toast", "postgres", "template0", "template1",
            "master", "model", "msdb", "tempdb", "distribution",
            "system", "sysaux", "undotbs1", "undo_1", "temp", "users",
            "dbsyncer", "dbdatasync"));

    /**
     * 实例级一键映射：枚举源连接器全部业务库，逐库生成同名目标库映射对。
     * 目标库默认同名，可选 targetDatabasePrefix 统一加前缀；已含于排除集合的库跳过。
     */
    @Override
    public List<DatabaseMappingVO> expandDatabases(Map<String, String> params) {
        String sourceConnectorId = params.get("sourceConnectorId");
        String targetConnectorId = params.get("targetConnectorId");
        Assert.hasText(sourceConnectorId, "源连接器不能为空");
        Assert.hasText(targetConnectorId, "目标连接器不能为空");
        String sourceSchema = params.get("sourceSchema");
        String targetDatabasePrefix = StringUtil.trimToEmpty(params.get("targetDatabasePrefix"));
        // 已选库（前端传入）跳过，避免与现有映射对重复
        Set<String> excluded = parseExcludeTables(params.get("excludedJson"));
        List<String> databases = connectorService.getDatabase(sourceConnectorId);
        if (CollectionUtils.isEmpty(databases)) {
            throw new BizException("源实例没有可枚举的库（或连接器不支持库级枚举）");
        }
        List<DatabaseMappingVO> result = new ArrayList<>();
        int index = 0;
        for (String database : databases) {
            if (database == null || StringUtil.isBlank(database)) {
                continue;
            }
            String lower = database.toLowerCase();
            if (SYSTEM_DATABASES.contains(lower) || excluded.contains(database) || excluded.contains(lower)) {
                continue;
            }
            DatabaseMappingVO vo = new DatabaseMappingVO();
            vo.setIndex(++index);
            vo.setSourceConnectorId(sourceConnectorId);
            vo.setTargetConnectorId(targetConnectorId);
            vo.setSourceDatabase(database);
            vo.setTargetDatabase(StringUtil.getIfBlank(targetDatabasePrefix + database, database));
            vo.setSourceSchema(sourceSchema);
            vo.setTableMappings(new ArrayList<>());
            result.add(vo);
        }
        if (CollectionUtils.isEmpty(result)) {
            throw new BizException("没有可映射的业务库（全部被系统库/已选排除规则过滤）");
        }
        return result;
    }

    /**
     * 一键接续增量：为整库迁移任务创建库级增量（CDC/定时）驱动。
     * 每个「源连接库 -> 目标连接库」簇生成一个驱动（模型=increment），
     * 表映射沿用迁移物化的 TableGroup（字段映射/执行命令按新驱动上下文重建），
     * 以迁移完成时刻为增量起点，不重复全量。
     */
    @Override
    public List<String> upgradeToIncrement(String id, boolean autoStart) {
        Assert.hasText(id, "任务 ID 不能为空");
        DatabaseSyncTask task = taskService.get(id);
        Assert.notNull(task, "任务不存在");
        if (taskService.isRunning(id)) {
            throw new BizException("整库迁移任务正在运行，等迁移完成后再接续增量");
        }
        // 幂等：已有关联驱动直接提示，避免重复创建多份增量
        if (!CollectionUtils.isEmpty(task.getMappingIds())) {
            List<String> alive = task.getMappingIds().stream()
                    .filter(mappingId -> mappingService.getMapping(mappingId) != null)
                    .collect(Collectors.toList());
            if (!CollectionUtils.isEmpty(alive)) {
                throw new BizException("该任务已创建接续驱动，请勿重复创建（见数据同步列表）");
            }
        }
        List<TableGroup> groups = new ArrayList<>();
        tableGroupProfile.pageScanTableGroups(id, 100, groups::addAll);
        if (CollectionUtils.isEmpty(groups)) {
            throw new BizException("任务没有表映射，无法接续增量");
        }
        // 按 库维度 聚簇：一个 Mapping 对应一组库映射
        Map<String, List<TableGroup>> clusters = new LinkedHashMap<>();
        for (TableGroup group : groups) {
            if (group == null || StringUtil.isBlank(group.buildDatabaseMappingKey())) {
                continue;
            }
            clusters.computeIfAbsent(group.buildDatabaseMappingKey(), k -> new ArrayList<>()).add(group);
        }
        if (clusters.isEmpty()) {
            throw new BizException("任务没有可接续的库映射簇");
        }

        List<String> mappingIds = new ArrayList<>();
        for (List<TableGroup> clusterGroups : clusters.values()) {
            mappingIds.add(createIncrementMappingForCluster(task, clusterGroups, autoStart));
        }
        // 关联驱动回写任务（任务 JSON 新增 mappingIds 关联字段）
        task.setMappingIds(mappingIds);
        taskService.edit(task);
        logger.info("整库迁移任务 [{}] 一键接续增量完成: created mappings={}", id, mappingIds);
        return mappingIds;
    }

    /**
     * 为单个库映射簇创建增量驱动：add（基础信息+连接）→ edit（增量模型+监听配置）→ 表映射物化 → 可选启动。
     */
    private String createIncrementMappingForCluster(DatabaseSyncTask task, List<TableGroup> clusterGroups, boolean autoStart) {
        // 取簇内一致的连接器实例（同一簇键保证同源同目标）
        TableGroup sample = clusterGroups.get(0);
        Connector sourceConnector = profileComponent.getConnector(sample.getSourceConnectorId());
        Assert.notNull(sourceConnector, "源连接器不存在");
        Assert.notNull(sourceConnector.getConfig(), "源连接器配置不存在");
        // 增量监听类型：优先 CDC(log)，权宜定时(timing)，均不支持时给出明确产品错误
        String listenerType = resolveListenerType(sourceConnector);

        String mappingName = buildIncrementMappingName(task, sample);
        Map<String, String> addParams = new HashMap<>();
        addParams.put(ConfigConstant.CONFIG_MODEL_NAME, mappingName);
        addParams.put("sourceConnectorId", sample.getSourceConnectorId());
        addParams.put("sourceDatabase", sample.getSourceDatabase());
        addParams.put("sourceSchema", sample.getSourceSchema());
        addParams.put("targetConnectorId", sample.getTargetConnectorId());
        addParams.put("targetDatabase", sample.getTargetDatabase());
        addParams.put("targetSchema", sample.getTargetSchema());
        String mappingId = mappingService.add(addParams);

        Map<String, String> editParams = new HashMap<>();
        editParams.put("id", mappingId);
        editParams.put("model", ModelEnum.INCREMENT.getCode());
        editParams.put("incrementStrategy", ListenerTypeEnum.isLog(listenerType) ? "Log" : "Timing");
        editParams.put("readNum", String.valueOf(task.getReadNum()));
        editParams.put("batchNum", String.valueOf(task.getBatchNum()));
        editParams.put("threadNum", String.valueOf(task.getThreadNum()));
        editParams.put("enableInsert", "on");
        editParams.put("enableUpdate", "on");
        editParams.put("enableDelete", "on");
        editParams.put("enableDDL", "on");
        if (task.isOverwriteData()) {
            editParams.put("forceUpdate", "on");
        }
        editParams.put("sourceConnectorId", sample.getSourceConnectorId());
        editParams.put("sourceDatabase", sample.getSourceDatabase());
        editParams.put("sourceSchema", sample.getSourceSchema());
        editParams.put("targetConnectorId", sample.getTargetConnectorId());
        editParams.put("targetDatabase", sample.getTargetDatabase());
        editParams.put("targetSchema", sample.getTargetSchema());
        try {
            mappingService.edit(editParams);
        } catch (Exception e) {
            // edit 失败回收半成品驱动，避免残留
            try {
                mappingService.remove(mappingId);
            } catch (Exception cleanup) {
                logger.warn("接续增量回收失败: {}", cleanup.getMessage());
            }
            throw new BizException("增量驱动配置失败: " + e.getMessage());
        }

        Mapping mapping = loadMapping(mappingId);
        // 表映射物化：新建 TableGroup（字段映射 + 执行命令按新驱动上下文重建）
        try {
            int index = 0;
            for (TableGroup migrated : clusterGroups) {
                index++;
                tableGroupProfile.addTableGroup(buildIncrementTableGroup(mapping, migrated, index));
            }
        } catch (Exception e) {
            try {
                mappingService.remove(mappingId);
            } catch (Exception cleanup) {
                logger.warn("接续增量回收失败: {}", cleanup.getMessage());
            }
            throw new BizException(String.format("增量表映射构建失败: %s", e.getMessage()));
        }

        if (autoStart) {
            try {
                mappingService.start(mappingId);
            } catch (Exception e) {
                // 启动失败不回滚已创建驱动，交由用户在数据同步页修复后启动
                throw new BizException(String.format("驱动 [%s] 已创建但启动失败: %s", mappingName, e.getMessage()));
            }
        }
        return mappingId;
    }

    private String buildIncrementMappingName(DatabaseSyncTask task, TableGroup sample) {
        String base = task.getName() != null ? task.getName() : "整库迁移";
        String dbs = (StringUtil.isNotBlank(sample.getSourceDatabase()) ? sample.getSourceDatabase() : "库")
                + "到" + (StringUtil.isNotBlank(sample.getTargetDatabase()) ? sample.getTargetDatabase() : "目标");
        String name = base + "-增量-" + dbs;
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    /**
     * 增量监听类型判定：log（CDC）优先，timing（定时轮询）兜底，均无则明确报错。
     */
    private String resolveListenerType(Connector sourceConnector) {
        String connectorType = sourceConnector.getConfig().getConnectorType();
        for (String listenerType : new String[]{ListenerTypeEnum.LOG.getType(), ListenerTypeEnum.TIMING.getType()}) {
            try {
                Listener listener = connectorFactory.getListener(connectorType, listenerType);
                if (listener != null) {
                    return listenerType;
                }
            } catch (Exception e) {
                logger.debug("连接器 [{}] 监听类型 [{}] 不可用: {}", connectorType, listenerType, e.getMessage());
            }
        }
        throw new BizException(String.format("源连接器 [%s] 不支持增量同步（无 CDC/定时监听能力），无法接续增量", connectorType));
    }

    private Mapping loadMapping(String mappingId) {
        Mapping mapping = profileComponent.getMapping(mappingId);
        Assert.notNull(mapping, "增量驱动不存在: " + mappingId);
        return mapping;
    }

    /**
     * 从迁移 TableGroup 构建增量 TableGroup：复制库级定位与源表元数据，
     * 连目标库回读表结构并以按名匹配生成字段映射，最后构建执行命令。
     */
    private TableGroup buildIncrementTableGroup(Mapping mapping, TableGroup migrated, int index) {
        TableGroup group = new TableGroup();
        BeanUtils.copyProperties(migrated, group);
        group.setId(String.valueOf(snowflakeIdWorker.nextId()));
        group.setTaskId(mapping.getId());
        group.setIndex(index);
        long now = Instant.now().toEpochMilli();
        group.setCreateTime(now);
        group.setUpdateTime(now);

        // 目标表结构（迁移阶段自动建表产物）：按 目标连接|库|构架 回读表元数据
        DefaultConnectorServiceContext context = ConnectorServiceContextUtil.buildConnectorServiceContext(
                mapping.getId(),
                group.getSourceConnectorId(), group.getSourceDatabase(), group.getSourceSchema(),
                group.getTargetConnectorId(), group.getTargetDatabase(), group.getTargetSchema(),
                false);
        if (group.getTargetTable() != null && StringUtil.isNotBlank(group.getTargetTable().getName())) {
            context.addTablePattern(group.getTargetTable().getName());
        }
        List<MetaInfo> metaInfos = parserComponent.getMetaInfo(context);
        Table targetMeta = null;
        for (MetaInfo metaInfo : metaInfos) {
            if (metaInfo != null && group.getTargetTable() != null
                    && StringUtil.equalsIgnoreCase(metaInfo.getTable(), group.getTargetTable().getName())) {
                targetMeta = new Table();
                targetMeta.setName(metaInfo.getTable());
                targetMeta.setType(metaInfo.getTableType());
                targetMeta.setColumn(metaInfo.getColumn());
                break;
            }
        }
        if (targetMeta == null) {
            throw new BizException(String.format("目标表 [%s] 在目标库中不存在，请先完成全量迁移或手动建表",
                    group.getTargetTable() != null ? group.getTargetTable().getName() : "?"));
        }
        group.setTargetTable(targetMeta);
        group.setFieldMapping(buildFieldMappingByName(group, targetMeta));
        Map<String, String> command = parserComponent.getCommand(mapping, group);
        if (CollectionUtils.isEmpty(command)) {
            throw new BizException(String.format("表 [%s] >> [%s] 执行命令构建失败",
                    group.getSourceTable() != null ? group.getSourceTable().getName() : "?",
                    group.getTargetTable().getName()));
        }
        group.setCommand(command);
        return group;
    }

    /**
     * 按名（忽略大小写）匹配构建字段映射，与整库迁移 Runner 兜底逻辑一致。
     */
    private List<FieldMapping> buildFieldMappingByName(TableGroup group, Table targetMeta) {
        List<Field> sourceColumns = group.getSourceTable() != null ? group.getSourceTable().getColumn() : null;
        if (CollectionUtils.isEmpty(sourceColumns)) {
            throw new BizException(String.format("源表 [%s] 缺少字段元数据，请重新保存库映射",
                    group.getSourceTable() != null ? group.getSourceTable().getName() : "?"));
        }
        Map<String, Field> targetColumnMap = new HashMap<>();
        if (targetMeta.getColumn() != null) {
            for (Field field : targetMeta.getColumn()) {
                if (field != null && StringUtil.isNotBlank(field.getName())) {
                    targetColumnMap.put(field.getName().toLowerCase(), field);
                }
            }
        }
        List<FieldMapping> fieldMapping = new ArrayList<>(sourceColumns.size());
        for (Field source : sourceColumns) {
            if (source == null || StringUtil.isBlank(source.getName())) {
                continue;
            }
            Field target = targetColumnMap.get(source.getName().toLowerCase());
            if (target == null) {
                target = new Field(source.getName(), source.getTypeName(), source.getType(), source.isPk(),
                        source.getColumnSize(), source.getRatio());
            }
            fieldMapping.add(new FieldMapping(source, target));
        }
        return fieldMapping;
    }
}
