package org.dbdatasync.parser.impl;

import org.dbdatasync.common.enums.CommonTaskTypeEnum;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.parser.TaskProfile;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.spi.DatabaseSyncDetailService;
import org.dbdatasync.sdk.spi.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 整库迁移任务默认调度服务（社区版开放）。
 */
@Service
public class DatabaseSyncTaskServiceImpl implements TaskService<DatabaseSyncTask> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private TaskProfile taskProfile;

    @Resource
    private DatabaseSyncDetailService databaseSyncDetailService;

    @Resource
    private DatabaseSyncTaskRunner databaseSyncTaskRunner;

    @Override
    public String add(DatabaseSyncTask task) {
        Assert.notNull(task, "任务不能为空");
        taskProfile.addTask(task);
        return task.getId();
    }

    @Override
    public String edit(DatabaseSyncTask task) {
        Assert.notNull(task, "任务不能为空");
        taskProfile.updateTask(task);
        return task.getId();
    }

    @Override
    public void delete(String id) {
        Assert.hasText(id, "任务ID不能为空");
        taskProfile.deleteTask(id);
    }

    @Override
    public DatabaseSyncTask get(String id) {
        Assert.hasText(id, "任务ID不能为空");
        return taskProfile.getTask(id, DatabaseSyncTask.class);
    }

    @Override
    public Paging search(Map<String, String> param, CommonTaskTypeEnum commonTaskTypeEnum) {
        int pageNum = parseInt(param.get("pageNum"), 1);
        int pageSize = parseInt(param.get("pageSize"), 10);
        String searchKey = param.get("searchKey");
        return taskProfile.queryTasks(DatabaseSyncTask.class, pageNum, pageSize, searchKey);
    }

    @Override
    public List<DatabaseSyncTask> getTaskAll(CommonTaskTypeEnum commonTaskTypeEnum) {
        List<DatabaseSyncTask> list = new ArrayList<>();
        taskProfile.pageScanTasks(DatabaseSyncTask.class, 100, list::addAll);
        return list;
    }

    @Override
    public void start(String id) {
        Assert.hasText(id, "任务ID不能为空");
        DatabaseSyncTask task = get(id);
        Assert.notNull(task, "任务不存在");
        if (!databaseSyncTaskRunner.tryAcquire(id)) {
            logger.warn("整库迁移任务 [{}] 已在运行中", id);
            return;
        }
        try {
            taskProfile.createRunDetailTable(id);
            databaseSyncDetailService.syncTaskTableMetaDetails(id);
        } catch (Exception e) {
            databaseSyncTaskRunner.release(id);
            throw new RuntimeException("启动整库迁移任务失败", e);
        }
        Thread worker = new Thread(() -> {
            try {
                databaseSyncTaskRunner.run(task);
            } catch (Exception e) {
                logger.error("整库迁移任务 [{}] 执行失败", id, e);
            } finally {
                databaseSyncTaskRunner.release(id);
            }
        });
        worker.setName("dbsync-task-" + id);
        worker.start();
        logger.info("整库迁移任务 [{}] 已启动", id);
    }

    @Override
    public void stop(String id) {
        Assert.hasText(id, "任务ID不能为空");
        databaseSyncTaskRunner.stop(id);
        databaseSyncDetailService.markRunningDetailsDone(id);
        logger.info("整库迁移任务 [{}] 已停止", id);
    }

    @Override
    public boolean isRunning(String taskId) {
        return databaseSyncTaskRunner.isRunning(taskId);
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
