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
import org.dbdatasync.parser.util.DiffItemRepushUtil;
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
import java.util.Collections;
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

    /** 单次批量重推上限（与 ValidateSyncExecutor.MAX_REPAIR_ITEMS 保持一致，repairContent 内部亦按此封顶） */
    private static final int BATCH_REPAIR_LIMIT = 100;

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
    public Map<String, Object> reviseDiffItem(String taskId, String detailId, int itemIndex) {
        Assert.hasText(taskId, "任务ID不能为空");
        Assert.hasText(detailId, "明细ID不能为空");
        Map<String, Object> detail = taskDetailProfile.getDetail(TaskDetailQuery.of(taskId).setDetailId(detailId));
        Assert.notNull(detail, "明细不存在");
        Map<String, Object> data = parseData(detail);
        Object content = data.get(ConfigConstant.TASK_CONTENT);
        Map<String, Object> item = DiffItemRepushUtil.selectItem(content, itemIndex);
        Assert.isTrue(DiffItemRepushUtil.isRepushable(item), "该差异条目不可重推（仅支持未订正的行数据差异）");
        int repaired = 0;
        try {
            repaired = validateSyncExecutor.repairContent(taskId, DiffItemRepushUtil.singleItemContent(item));
        } catch (Exception e) {
            logger.warn("订正校验任务 [{}] 单条重推失败: {}", taskId, e.getMessage());
        }
        long diffTotal = getLong(data, ConfigConstant.TASK_DIFF_TOTAL);
        long fixedTotal = DiffItemRepushUtil.nextFixedTotal(diffTotal, getLong(data, ConfigConstant.TASK_FIXED_TOTAL), repaired);
        data.put(ConfigConstant.TASK_FIXED_TOTAL, fixedTotal);
        data.put(ConfigConstant.TASK_CONTENT, content);
        detail.put(ConfigConstant.BINLOG_DATA, JsonUtil.objToJson(data));
        detail.put(ConfigConstant.DETAIL_IS_SUCCESS, DiffItemRepushUtil.isDone(diffTotal, fixedTotal) ? 1 : 0);
        detail.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, System.currentTimeMillis());
        storageService.editBatch(StorageEnum.TASK_DETAIL, taskId, Collections.singletonList(detail));
        Map<String, Object> fresh = taskDetailProfile.getDetail(TaskDetailQuery.of(taskId).setDetailId(detailId));
        return fresh != null ? fresh : detail;
    }

    @Override
    public Map<String, Object> reviseDiffItems(String taskId, String detailId, List<Integer> itemIndexes) {
        Assert.hasText(taskId, "任务ID不能为空");
        Assert.hasText(detailId, "明细ID不能为空");
        if (itemIndexes == null || itemIndexes.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一条差异条目");
        }
        if (itemIndexes.size() > BATCH_REPAIR_LIMIT) {
            throw new IllegalArgumentException("单次批量重推最多 " + BATCH_REPAIR_LIMIT + " 条");
        }
        Map<String, Object> detail = taskDetailProfile.getDetail(TaskDetailQuery.of(taskId).setDetailId(detailId));
        Assert.notNull(detail, "明细不存在");
        Map<String, Object> data = parseData(detail);
        Object content = data.get(ConfigConstant.TASK_CONTENT);

        List<Map<String, Object>> validItems = new ArrayList<>();
        List<Integer> validIndexes = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Integer raw : itemIndexes) {
            int idx = raw == null ? -1 : raw;
            Map<String, Object> item;
            try {
                item = DiffItemRepushUtil.selectItem(content, idx);
            } catch (IllegalArgumentException e) {
                failures.add(failureEntry(idx, e.getMessage()));
                continue;
            }
            if (!DiffItemRepushUtil.isRepushable(item)) {
                failures.add(failureEntry(idx, "该差异条目不可重推（已订正或不可修）"));
                continue;
            }
            validItems.add(item);
            validIndexes.add(idx);
        }

        int repaired = 0;
        if (!validItems.isEmpty()) {
            try {
                repaired = validateSyncExecutor.repairContent(taskId, validItems);
            } catch (Exception e) {
                logger.warn("订正校验任务 [{}] 批量重推执行失败: {}", taskId, e.getMessage());
            }
            // repairContent 已在每条 item.msg 就地标记 (已订正)/(订正失败)，逐条判定结果
            for (int i = 0; i < validItems.size(); i++) {
                Object m = validItems.get(i).get("msg");
                String msg = m == null ? "" : String.valueOf(m);
                if (!msg.contains("(已订正)")) {
                    String reason = msg.contains("(订正失败")
                            ? "订正失败：" + stripRepairFailReason(msg) : "数据不完整，无法订正";
                    failures.add(failureEntry(validIndexes.get(i), reason));
                }
            }
        }

        long diffTotal = getLong(data, ConfigConstant.TASK_DIFF_TOTAL);
        long fixedTotal = DiffItemRepushUtil.nextFixedTotal(diffTotal,
                getLong(data, ConfigConstant.TASK_FIXED_TOTAL), repaired);
        data.put(ConfigConstant.TASK_FIXED_TOTAL, fixedTotal);
        data.put(ConfigConstant.TASK_CONTENT, content);
        detail.put(ConfigConstant.BINLOG_DATA, JsonUtil.objToJson(data));
        detail.put(ConfigConstant.DETAIL_IS_SUCCESS, DiffItemRepushUtil.isDone(diffTotal, fixedTotal) ? 1 : 0);
        detail.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, System.currentTimeMillis());
        storageService.editBatch(StorageEnum.TASK_DETAIL, taskId, Collections.singletonList(detail));

        Map<String, Object> fresh = taskDetailProfile.getDetail(TaskDetailQuery.of(taskId).setDetailId(detailId));
        Map<String, Object> result = new HashMap<>();
        result.put("successCount", repaired);
        result.put("failureCount", failures.size());
        result.put("total", itemIndexes.size());
        result.put("failures", failures);
        result.put("detail", fresh != null ? fresh : detail);
        return result;
    }

    private static Map<String, Object> failureEntry(int index, String reason) {
        Map<String, Object> f = new HashMap<>(2);
        f.put("index", index);
        f.put("reason", reason);
        return f;
    }

    private static String stripRepairFailReason(String msg) {
        int start = msg.indexOf("(订正失败:");
        if (start < 0) {
            return "影响行数为0";
        }
        int end = msg.indexOf(')', start);
        String inner = end > start ? msg.substring(start + "(订正失败:".length(), end) : msg.substring(start);
        return inner.isEmpty() ? "影响行数为0" : inner;
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
