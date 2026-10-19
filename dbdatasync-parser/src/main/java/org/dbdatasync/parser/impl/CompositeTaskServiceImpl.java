package org.dbdatasync.parser.impl;

import org.dbdatasync.common.enums.CommonTaskTypeEnum;
import org.dbdatasync.common.model.ConfigModel;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.model.ValidateSyncTask;
import org.dbdatasync.sdk.spi.TaskService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用任务服务组合实现，用于兼容需要 TaskService&lt;ConfigModel&gt; 的场景（如启动恢复）。
 */
@Primary
@Service
public class CompositeTaskServiceImpl implements TaskService<ConfigModel> {

    @Resource
    private TaskService<ValidateSyncTask> validateSyncTaskService;

    @Resource
    private TaskService<DatabaseSyncTask> databaseSyncTaskService;

    @Override
    public String add(ConfigModel task) {
        throw new UnsupportedOperationException("请使用具体的任务服务创建任务");
    }

    @Override
    public String edit(ConfigModel task) {
        throw new UnsupportedOperationException("请使用具体的任务服务编辑任务");
    }

    @Override
    public void delete(String id) {
        validateSyncTaskService.delete(id);
        databaseSyncTaskService.delete(id);
    }

    @Override
    public ConfigModel get(String id) {
        ConfigModel task = validateSyncTaskService.get(id);
        if (task != null) {
            return task;
        }
        return databaseSyncTaskService.get(id);
    }

    @Override
    public Paging search(Map<String, String> param, CommonTaskTypeEnum commonTaskTypeEnum) {
        if (commonTaskTypeEnum == CommonTaskTypeEnum.DATABASE_SYNC) {
            return databaseSyncTaskService.search(param, commonTaskTypeEnum);
        }
        return validateSyncTaskService.search(param, commonTaskTypeEnum);
    }

    @Override
    public List<ConfigModel> getTaskAll(CommonTaskTypeEnum commonTaskTypeEnum) {
        if (commonTaskTypeEnum == CommonTaskTypeEnum.VALIDATE_SYNC) {
            List<ValidateSyncTask> list = validateSyncTaskService.getTaskAll(commonTaskTypeEnum);
            return new ArrayList<>(list);
        }
        if (commonTaskTypeEnum == CommonTaskTypeEnum.DATABASE_SYNC) {
            List<DatabaseSyncTask> list = databaseSyncTaskService.getTaskAll(commonTaskTypeEnum);
            return new ArrayList<>(list);
        }
        List<ConfigModel> all = new ArrayList<>();
        all.addAll(validateSyncTaskService.getTaskAll(CommonTaskTypeEnum.VALIDATE_SYNC));
        all.addAll(databaseSyncTaskService.getTaskAll(CommonTaskTypeEnum.DATABASE_SYNC));
        return all;
    }

    @Override
    public void start(String id) {
        ConfigModel task = get(id);
        Assert.notNull(task, "任务不存在");
        if (task instanceof DatabaseSyncTask) {
            databaseSyncTaskService.start(id);
            return;
        }
        validateSyncTaskService.start(id);
    }

    @Override
    public void stop(String id) {
        ConfigModel task = get(id);
        if (task instanceof DatabaseSyncTask) {
            databaseSyncTaskService.stop(id);
            return;
        }
        validateSyncTaskService.stop(id);
    }

    @Override
    public boolean isRunning(String taskId) {
        return validateSyncTaskService.isRunning(taskId) || databaseSyncTaskService.isRunning(taskId);
    }
}
