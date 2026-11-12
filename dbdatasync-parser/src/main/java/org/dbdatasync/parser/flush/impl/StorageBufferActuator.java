/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.parser.flush.impl;

import org.dbdatasync.common.config.StorageConfig;
import org.dbdatasync.common.enums.TaskLevelEnum;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.flush.AbstractBufferActuator;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.StorageRequest;
import org.dbdatasync.parser.model.StorageResponse;
import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 持久化执行器
 *
 * @Version 1.0.0
 * @Author AE86
 * @Date 2023-03-27 16:50
 */
@Component
public final class StorageBufferActuator extends AbstractBufferActuator<StorageRequest, StorageResponse> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private StorageConfig storageConfig;

    @Resource
    private StorageService storageService;

    @Resource
    private Executor storageExecutor;

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private MetaProfile metaProfile;

    @PostConstruct
    private void init() {
        setConfig(storageConfig);
        buildConfig();
    }

    @Override
    protected String getPartitionKey(StorageRequest request) {
        return request.getTaskDetailShardId();
    }

    @Override
    protected void partition(StorageRequest request, StorageResponse response) {
        response.setTaskDetailShardId(request.getTaskDetailShardId());
        response.getDataList().add(request.getRow());
    }

    @Override
    public void pull(StorageResponse response) {
        String shardId = response.getTaskDetailShardId();
        // 严格走库 + 明细分表：写入 DBDataSync_task_detail_{taskId}
        Runnable write = () -> storageService.addBatch(StorageEnum.TASK_DETAIL, shardId, response.getDataList());
        try {
            storageExecutor.execute(write);
        } catch (RejectedExecutionException e) {
            // Wave30：线程池关闭/饱和时旧实现直接丢掉整批明细。宁可同步落库阻塞生产者，也不丢数据。
            logger.warn("明细写线程池拒绝任务，改为同步落库: shardId={}, rows={}", shardId, response.getDataList().size());
            write.run();
        }
    }

    @Override
    protected void offerFailed(BlockingQueue<StorageRequest> queue, StorageRequest request) {
        // Wave30：旧实现只 log 一行就丢掉该明细，队列一满即静默少数据。
        // 明细写入属于不可丢路径，改为限时阻塞入队形成背压；仍失败才显式报错并保留行内容摘要。
        String shardId = request.getTaskDetailShardId();
        boolean accepted = false;
        try {
            accepted = queue.offer(request, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!accepted) {
            Meta meta = metaProfile.getMetaByTaskId(shardId, TaskLevelEnum.TASK);
            if (meta == null) {
                meta = metaProfile.getMeta(shardId);
            }
            String name = "";
            if (meta != null) {
                Mapping mapping = profileComponent.getMapping(meta.getTaskId());
                if (mapping != null) {
                    name = mapping.getName();
                }
            }
            logger.error("明细队列持续满载，丢弃 1 条明细: task={}, shardId={}, data={}", name, shardId, request.getRow());
        }
    }

    @Override
    public Executor getExecutor() {
        return storageExecutor;
    }
}