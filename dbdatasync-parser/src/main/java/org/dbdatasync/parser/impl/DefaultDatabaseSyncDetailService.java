package org.dbdatasync.parser.impl;

import org.dbdatasync.common.model.Paging;
import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.common.util.UUIDUtil;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.TaskDetailProfile;
import org.dbdatasync.parser.TaskProfile;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.model.TaskDetailQuery;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.spi.DatabaseSyncDetailService;
import org.dbdatasync.sdk.storage.StorageService;
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

/**
 * 整库迁移任务明细默认实现（社区版开放）。
 */
@Service
public class DefaultDatabaseSyncDetailService implements DatabaseSyncDetailService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private TaskProfile taskProfile;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private TaskDetailProfile taskDetailProfile;

    @Resource
    private StorageService storageService;

    @Override
    public Paging result(Map<String, String> params) {
        String taskId = params.get(ConfigConstant.TASK_ID);
        Assert.hasText(taskId, "任务ID不能为空");
        int pageNum = parseInt(params.get("pageNum"), 1);
        int pageSize = parseInt(params.get("pageSize"), 10);
        return taskDetailProfile.queryResults(TaskDetailQuery.of(taskId).setPage(pageNum, pageSize));
    }

    @Override
    public void syncTaskTableMetaDetails(String taskId) {
        Assert.hasText(taskId, "任务ID不能为空");
        DatabaseSyncTask task = taskProfile.getTask(taskId, DatabaseSyncTask.class);
        Assert.notNull(task, "任务不存在");

        List<TableGroup> tableGroups = getTableGroups(taskId);
        if (CollectionUtils.isEmpty(tableGroups)) {
            logger.warn("整库迁移任务 [{}] 无表映射，跳过初始化明细", taskId);
            return;
        }

        taskProfile.clearRunData(taskId);

        List<Map> rows = new ArrayList<>();
        for (TableGroup tg : tableGroups) {
            String targetTableName = tg.getTargetTable() != null ? tg.getTargetTable().getName() : StringUtil.EMPTY;
            Map<String, Object> data = new HashMap<>();
            data.put(ConfigConstant.TASK_SOURCE_TOTAL, 0L);
            data.put(ConfigConstant.TASK_TARGET_TOTAL, 0L);
            data.put(ConfigConstant.TASK_DIFF_TOTAL, 0L);
            data.put(ConfigConstant.TASK_FIXED_TOTAL, 0L);
            data.put(ConfigConstant.TASK_CONTENT, StringUtil.EMPTY);

            Map<String, Object> row = new HashMap<>();
            row.put(ConfigConstant.CONFIG_MODEL_ID, UUIDUtil.getUUID());
            row.put(ConfigConstant.DATA_TABLE_GROUP_ID, tg.getId());
            row.put(ConfigConstant.CONFIG_MODEL_TYPE, ConfigConstant.DATABASE_SYNC);
            row.put(ConfigConstant.DETAIL_TARGET_TABLE, targetTableName);
            row.put(ConfigConstant.DETAIL_IS_SUCCESS, 0);
            row.put(ConfigConstant.DATA_ERROR, StringUtil.EMPTY);
            row.put(ConfigConstant.CONFIG_MODEL_CREATE_TIME, System.currentTimeMillis());
            row.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, System.currentTimeMillis());
            row.put(ConfigConstant.BINLOG_DATA, JsonUtil.objToJson(data));
            rows.add(row);
        }

        storageService.addBatch(StorageEnum.TASK_DETAIL, taskId, rows);
    }

    @Override
    public void resetTaskDetailsForNewRound(String taskId) {
        Assert.hasText(taskId, "任务ID不能为空");
        Map<String, String> params = new HashMap<>();
        params.put(ConfigConstant.TASK_ID, taskId);
        Paging paging = result(params);
        if (CollectionUtils.isEmpty(paging.getData())) {
            return;
        }
        List<Map> rows = new ArrayList<>();
        for (Object item : paging.getData()) {
            Map<String, Object> row = (Map<String, Object>) item;
            row.put(ConfigConstant.DETAIL_IS_SUCCESS, 0);
            row.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, System.currentTimeMillis());
            Map<String, Object> data = parseData(row);
            data.put(ConfigConstant.TASK_SOURCE_TOTAL, 0L);
            data.put(ConfigConstant.TASK_TARGET_TOTAL, 0L);
            data.put(ConfigConstant.TASK_DIFF_TOTAL, 0L);
            data.put(ConfigConstant.TASK_FIXED_TOTAL, 0L);
            data.put(ConfigConstant.TASK_CONTENT, StringUtil.EMPTY);
            row.put(ConfigConstant.BINLOG_DATA, JsonUtil.objToJson(data));
            rows.add(row);
        }
        storageService.editBatch(StorageEnum.TASK_DETAIL, taskId, rows);
    }

    @Override
    public void markRunningDetailsDone(String taskId) {
        Assert.hasText(taskId, "任务ID不能为空");
        Map<String, String> params = new HashMap<>();
        params.put(ConfigConstant.TASK_ID, taskId);
        Paging paging = result(params);
        if (CollectionUtils.isEmpty(paging.getData())) {
            return;
        }
        List<Map> rows = new ArrayList<>();
        for (Object item : paging.getData()) {
            Map<String, Object> row = (Map<String, Object>) item;
            row.put(ConfigConstant.DETAIL_IS_SUCCESS, 1);
            row.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, System.currentTimeMillis());
            rows.add(row);
        }
        storageService.editBatch(StorageEnum.TASK_DETAIL, taskId, rows);
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
        if (data instanceof String && StringUtil.isNotBlank((String) data)) {
            return JsonUtil.parseMap((String) data);
        }
        return new HashMap<>();
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
