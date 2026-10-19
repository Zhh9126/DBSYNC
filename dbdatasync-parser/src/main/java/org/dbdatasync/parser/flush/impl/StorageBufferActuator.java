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
        storageExecutor.execute(() -> storageService.addBatch(StorageEnum.TASK_DETAIL, shardId, response.getDataList()));
    }

    @Override
    protected void offerFailed(BlockingQueue<StorageRequest> queue, StorageRequest request) {
        String shardId = request.getTaskDetailShardId();
        Meta meta = metaProfile.getMetaByTaskId(shardId, TaskLevelEnum.TASK);
        if (meta == null) {
            meta = metaProfile.getMeta(shardId);
        }
        if (meta != null) {
            Mapping mapping = profileComponent.getMapping(meta.getTaskId());
            if (mapping != null) {
                logger.info("{}, data={}", mapping.getName(), request.getRow());
            }
        }
    }

    @Override
    public Executor getExecutor() {
        return storageExecutor;
    }
}