package org.dbdatasync.parser.impl;

import org.dbdatasync.common.enums.CommonTaskTypeEnum;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.parser.TaskProfile;
import org.dbdatasync.sdk.model.ValidateSyncTask;
import org.dbdatasync.sdk.spi.TaskService;
import org.dbdatasync.sdk.spi.ValidateSyncDetailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订正校验任务默认调度服务（社区版开放）。
 */
@Service
public class ValidateSyncTaskServiceImpl implements TaskService<ValidateSyncTask> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private TaskProfile taskProfile;

    @Resource
    private ValidateSyncDetailService validateSyncDetailService;

    @Resource
    private ValidateSyncExecutor validateSyncExecutor;

    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    @Override
    public String add(ValidateSyncTask task) {
        Assert.notNull(task, "任务不能为空");
        taskProfile.addTask(task);
        return task.getId();
    }

    @Override
    public String edit(ValidateSyncTask task) {
        Assert.notNull(task, "任务不能为空");
        taskProfile.updateTask(task);
        return task.getId();
    }

    @Override
    public void delete(String id) {
        Assert.hasText(id, "任务ID不能为空");
        taskProfile.deleteTask(id);
        running.remove(id);
    }

    @Override
    public ValidateSyncTask get(String id) {
        Assert.hasText(id, "任务ID不能为空");
        return taskProfile.getTask(id, ValidateSyncTask.class);
    }

    @Override
    public Paging search(Map<String, String> param, CommonTaskTypeEnum commonTaskTypeEnum) {
        int pageNum = parseInt(param.get("pageNum"), 1);
        int pageSize = parseInt(param.get("pageSize"), 10);
        String searchKey = param.get("searchKey");
        return taskProfile.queryTasks(ValidateSyncTask.class, pageNum, pageSize, searchKey);
    }

    @Override
    public List<ValidateSyncTask> getTaskAll(CommonTaskTypeEnum commonTaskTypeEnum) {
        List<ValidateSyncTask> list = new ArrayList<>();
        taskProfile.pageScanTasks(ValidateSyncTask.class, 100, list::addAll);
        return list;
    }

    @Override
    public void start(String id) {
        Assert.hasText(id, "任务ID不能为空");
        ValidateSyncTask task = get(id);
        Assert.notNull(task, "任务不存在");
        if (Boolean.TRUE.equals(running.putIfAbsent(id, Boolean.TRUE))) {
            logger.warn("订正校验任务 [{}] 已在运行中", id);
            return;
        }
        try {
            taskProfile.createRunDetailTable(id);
            validateSyncDetailService.syncTaskTableMetaDetails(id);
            logger.info("订正校验任务 [{}] 已启动", id);
            Thread worker = new Thread(() -> {
                try {
                    validateSyncExecutor.run(id);
                } catch (Exception e) {
                    logger.error("订正校验任务 [{}] 执行异常", id, e);
                } finally {
                    running.remove(id);
                }
            }, "validate-sync-" + id);
            worker.setDaemon(true);
            worker.start();
        } catch (Exception e) {
            running.remove(id);
            throw new RuntimeException("启动订正校验任务失败", e);
        }
    }

    @Override
    public void stop(String id) {
        Assert.hasText(id, "任务ID不能为空");
        running.remove(id);
        validateSyncExecutor.requestStop(id);
        validateSyncDetailService.markRunningDetailsDone(id);
        logger.info("订正校验任务 [{}] 已停止", id);
    }

    @Override
    public boolean isRunning(String taskId) {
        return Boolean.TRUE.equals(running.get(taskId));
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
