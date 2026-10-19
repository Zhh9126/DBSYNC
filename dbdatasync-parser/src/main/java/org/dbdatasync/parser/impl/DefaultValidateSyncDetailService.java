package org.dbdatasync.parser.impl;

import org.dbdatasync.common.enums.CommonTaskStatusEnum;
import org.dbdatasync.common.enums.TaskLevelEnum;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.common.util.UUIDUtil;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.TaskDetailProfile;
import org.dbdatasync.parser.TaskProfile;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.model.TaskDetailQuery;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.model.ValidateSyncTask;
import org.dbdatasync.sdk.spi.ValidateSyncDetailService;
import org.dbdatasync.sdk.storage.StorageService;
import org.dbdatasync.sdk.util.TaskDetailUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订正校验任务明细默认实现（社区版开放）。
 */
@Service
public class DefaultValidateSyncDetailService implements ValidateSyncDetailService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /** 全量扫描明细行时的页大小（须覆盖表组×类型行数上限） */
    private static final int SCAN_PAGE_SIZE = 100000;

    @Resource
    private TaskProfile taskProfile;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private TaskDetailProfile taskDetailProfile;

    @Resource
    private StorageService storageService;

    @Resource
    private ValidateSyncExecutor validateSyncExecutor;

    @Override
    public Paging result(Map<String, String> params) {
        String taskId = params.get(ConfigConstant.TASK_ID);
        Assert.hasText(taskId, "任务ID不能为空");
        int pageNum = parseInt(params.get("pageNum"), 1);
        int pageSize = parseInt(params.get("pageSize"), 10);
        return taskDetailProfile.queryResults(TaskDetailQuery.of(taskId).setPage(pageNum, pageSize));
    }

    @Override
    public Map<String, Object> manualRevise(String taskId, String detailId) {
        Assert.hasText(taskId, "任务ID不能为空");
        Assert.hasText(detailId, "明细ID不能为空");

        Map<String, Object> detail = taskDetailProfile.getDetail(TaskDetailQuery.of(taskId).setDetailId(detailId));
        Assert.notNull(detail, "明细不存在");

        Map<String, Object> data = parseData(detail);
        long diffTotal = getLong(data, ConfigConstant.TASK_DIFF_TOTAL);
        long fixedTotal = getLong(data, ConfigConstant.TASK_FIXED_TOTAL);
        Object content = data.get(ConfigConstant.TASK_CONTENT);

        // 真正执行订正：按差异明细写回目标库（INSERT/UPDATE/DELETE）
        int repaired = 0;
        try {
            repaired = validateSyncExecutor.repairContent(taskId, content);
        } catch (Exception e) {
            logger.warn("订正校验任务 [{}] 手动订正执行失败: {}", taskId, e.getMessage());
        }

        String rowType = detail.get(ConfigConstant.CONFIG_MODEL_TYPE) == null
                ? StringUtil.EMPTY : String.valueOf(detail.get(ConfigConstant.CONFIG_MODEL_TYPE));
        if (repaired > 0) {
            fixedTotal = Math.min(diffTotal, fixedTotal + repaired);
        } else if (!ValidateSyncExecutor.CHECK_TYPE_ROW_DATA.equals(rowType) && fixedTotal < diffTotal) {
            // 结构/索引差异无法 SQL 订正：整行确认
            fixedTotal = diffTotal;
        } else if (fixedTotal < diffTotal) {
            // 行数据差异均不可修时的兜底：确认一条
            fixedTotal++;
        }
        data.put(ConfigConstant.TASK_FIXED_TOTAL, fixedTotal);
        // 保留差异上下文（订正结果就地标记在 content 项 msg 内），不再覆盖
        data.put(ConfigConstant.TASK_CONTENT, content);

        detail.put(ConfigConstant.BINLOG_DATA, JsonUtil.objToJson(data));
        detail.put(ConfigConstant.DETAIL_IS_SUCCESS, fixedTotal >= diffTotal ? 1 : 0);
        detail.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, System.currentTimeMillis());

        List<Map> rows = new ArrayList<>();
        rows.add(detail);
        storageService.editBatch(StorageEnum.TASK_DETAIL, taskId, rows);

        // 重新装配返回（避免行内残留旧铺平字段 content/diffTotal 等）
        Map<String, Object> fresh = taskDetailProfile.getDetail(TaskDetailQuery.of(taskId).setDetailId(detailId));
        return fresh != null ? fresh : detail;
    }

    @Override
    public void syncTaskTableMetaDetails(String taskId) {
        Assert.hasText(taskId, "任务ID不能为空");
        ValidateSyncTask task = taskProfile.getTask(taskId, ValidateSyncTask.class);
        Assert.notNull(task, "任务不存在");

        List<TableGroup> tableGroups = getTableGroups(taskId);
        if (CollectionUtils.isEmpty(tableGroups)) {
            logger.warn("订正校验任务 [{}] 无表映射，跳过初始化明细", taskId);
            return;
        }

        taskProfile.clearRunData(taskId);

        List<String> enabledTypes = enabledCheckTypes(task);
        if (enabledTypes.isEmpty()) {
            logger.warn("订正校验任务 [{}] 未开启任何校验范围，跳过初始化明细", taskId);
            return;
        }

        // 契约：每种开启的校验类型一行（rowData/tableSchema/index）
        List<Map> rows = new ArrayList<>();
        for (TableGroup tg : tableGroups) {
            String targetTableName = tg.getTargetTable() != null ? tg.getTargetTable().getName() : StringUtil.EMPTY;
            for (String type : enabledTypes) {
                rows.add(buildDetailRow(tg.getId(), type, targetTableName));
            }
        }
        storageService.addBatch(StorageEnum.TASK_DETAIL, taskId, rows);
    }

    private List<String> enabledCheckTypes(ValidateSyncTask task) {
        List<String> types = new ArrayList<>();
        if (task.isEnableRowData()) {
            types.add(ValidateSyncExecutor.CHECK_TYPE_ROW_DATA);
        }
        if (task.isEnableSchema()) {
            types.add(ValidateSyncExecutor.CHECK_TYPE_TABLE_SCHEMA);
        }
        if (task.isEnableIndex()) {
            types.add(ValidateSyncExecutor.CHECK_TYPE_INDEX);
        }
        return types;
    }

    private Map<String, Object> buildDetailRow(String tableGroupId, String type, String targetTableName) {
        Map<String, Object> data = new HashMap<>();
        data.put(ConfigConstant.TASK_SOURCE_TOTAL, 0L);
        data.put(ConfigConstant.TASK_TARGET_TOTAL, 0L);
        data.put(ConfigConstant.TASK_DIFF_TOTAL, 0L);
        data.put(ConfigConstant.TASK_FIXED_TOTAL, 0L);
        data.put(ConfigConstant.TASK_CONTENT, StringUtil.EMPTY);
        data.put(ConfigConstant.TASK_STATUS, CommonTaskStatusEnum.READY.getCode());

        Map<String, Object> row = new HashMap<>();
        row.put(ConfigConstant.CONFIG_MODEL_ID, UUIDUtil.getUUID());
        row.put(ConfigConstant.DATA_TABLE_GROUP_ID, tableGroupId);
        row.put(ConfigConstant.CONFIG_MODEL_TYPE, type);
        row.put(ConfigConstant.DETAIL_TARGET_TABLE, targetTableName);
        row.put(ConfigConstant.DETAIL_IS_SUCCESS, 0);
        row.put(ConfigConstant.DATA_ERROR, StringUtil.EMPTY);
        row.put(ConfigConstant.CONFIG_MODEL_CREATE_TIME, System.currentTimeMillis());
        row.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, System.currentTimeMillis());
        row.put(ConfigConstant.BINLOG_DATA, JsonUtil.objToJson(data));
        return row;
    }

    @Override
    public void resetTaskDetailsForNewRound(String taskId) {
        Assert.hasText(taskId, "任务ID不能为空");
        List<Map<String, Object>> allRows = scanAllRows(taskId);
        if (allRows.isEmpty()) {
            return;
        }
        List<Map> rows = new ArrayList<>();
        for (Map<String, Object> row : allRows) {
            row.put(ConfigConstant.DETAIL_IS_SUCCESS, 0);
            row.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, System.currentTimeMillis());
            Map<String, Object> data = parseData(row);
            data.put(ConfigConstant.TASK_DIFF_TOTAL, 0L);
            data.put(ConfigConstant.TASK_FIXED_TOTAL, 0L);
            data.put(ConfigConstant.TASK_CONTENT, StringUtil.EMPTY);
            data.put(ConfigConstant.TASK_STATUS, CommonTaskStatusEnum.READY.getCode());
            row.put(ConfigConstant.BINLOG_DATA, JsonUtil.objToJson(data));
            rows.add(row);
        }
        storageService.editBatch(StorageEnum.TASK_DETAIL, taskId, rows);
        // 表明细 Meta 重置为未运行、计数清零
        for (TableGroup tg : getTableGroups(taskId)) {
            Meta meta = metaProfile.getMetaByTaskId(tg.getId(), TaskLevelEnum.TASK_DETAIL);
            if (meta == null) {
                continue;
            }
            meta.setState(CommonTaskStatusEnum.READY.getCode());
            meta.setTotal(new AtomicLong(0));
            meta.setSuccess(new AtomicLong(0));
            meta.setFail(new AtomicLong(0));
            meta.setDiff(new AtomicLong(0));
            meta.setFixed(new AtomicLong(0));
            metaProfile.updateMeta(meta);
        }
    }

    @Override
    public void markRunningDetailsDone(String taskId) {
        Assert.hasText(taskId, "任务ID不能为空");
        List<Map<String, Object>> allRows = scanAllRows(taskId);
        if (allRows.isEmpty()) {
            return;
        }
        List<Map> rows = new ArrayList<>();
        for (Map<String, Object> row : allRows) {
            Map<String, Object> data = parseData(row);
            long diffTotal = getLong(data, ConfigConstant.TASK_DIFF_TOTAL);
            long fixedTotal = getLong(data, ConfigConstant.TASK_FIXED_TOTAL);
            // 收尾残留运行中/停止中状态 → 未运行
            Object status = data.get(ConfigConstant.TASK_STATUS);
            int statusCode = status instanceof Number
                    ? ((Number) status).intValue()
                    : parseInt(status == null ? StringUtil.EMPTY : String.valueOf(status), CommonTaskStatusEnum.READY.getCode());
            if (CommonTaskStatusEnum.isRunning(statusCode)) {
                data.put(ConfigConstant.TASK_STATUS, CommonTaskStatusEnum.READY.getCode());
                row.put(ConfigConstant.BINLOG_DATA, JsonUtil.objToJson(data));
            }
            row.put(ConfigConstant.DETAIL_IS_SUCCESS, fixedTotal >= diffTotal ? 1 : 0);
            row.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, System.currentTimeMillis());
            rows.add(row);
        }
        storageService.editBatch(StorageEnum.TASK_DETAIL, taskId, rows);
        // 表明细 Meta 收尾：运行中/停止中 → 未运行
        for (TableGroup tg : getTableGroups(taskId)) {
            Meta meta = metaProfile.getMetaByTaskId(tg.getId(), TaskLevelEnum.TASK_DETAIL);
            if (meta != null && CommonTaskStatusEnum.isRunning(meta.getState())) {
                meta.setState(CommonTaskStatusEnum.READY.getCode());
                metaProfile.updateMeta(meta);
            }
        }
        Meta taskMeta = metaProfile.getMetaByTaskId(taskId, TaskLevelEnum.TASK);
        if (taskMeta != null && CommonTaskStatusEnum.isRunning(taskMeta.getState())) {
            taskMeta.setState(CommonTaskStatusEnum.READY.getCode());
            metaProfile.updateMeta(taskMeta);
        }
    }

    private List<Map<String, Object>> scanAllRows(String taskId) {
        Map<String, String> params = new HashMap<>();
        params.put(ConfigConstant.TASK_ID, taskId);
        params.put("pageSize", String.valueOf(SCAN_PAGE_SIZE));
        Paging paging = result(params);
        List<Map<String, Object>> rows = new ArrayList<>();
        Object data = paging.getData();
        if (data instanceof List) {
            for (Object o : (List) data) {
                if (o instanceof Map) {
                    rows.add((Map<String, Object>) o);
                }
            }
        }
        return rows;
    }

    private List<TableGroup> getTableGroups(String taskId) {
        List<String> ids = tableGroupProfile.listTableGroupIds(taskId);
        if (CollectionUtils.isEmpty(ids)) {
            return new ArrayList<>();
        }
        List<TableGroup> list = new ArrayList<>(ids.size());
        for (String id : ids) {
            TableGroup tg = tableGroupProfile.getTableGroup(id);
            if (tg != null) {
                list.add(tg);
            }
        }
        return list;
    }

    private Map<String, Object> parseData(Map<String, Object> row) {
        Object data = row.get(ConfigConstant.BINLOG_DATA);
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        // H2 BLOB 列读出为 byte[]，统一交给 TaskDetailUtil 反序列化
        if (data instanceof byte[]) {
            return TaskDetailUtil.deserializeContent(data);
        }
        if (data instanceof String && StringUtil.isNotBlank((String) data)) {
            return JsonUtil.parseMap((String) data);
        }
        return new HashMap<>();
    }

    private long getLong(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private int parseInt(String val, int defaultVal) {
        if (val == null || val.trim().isEmpty()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
