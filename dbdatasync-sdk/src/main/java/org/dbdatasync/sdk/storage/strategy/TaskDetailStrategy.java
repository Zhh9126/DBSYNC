/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.sdk.storage.strategy;

import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.storage.Strategy;

/**
 * 任务执行明细：按任务分表(每个任务一张表, 逻辑同旧 DBDataSync_data)
 *
 * @Author AE86
 * @Version 1.0.0
 * @Date 2026-07-17 17:00
 */
public final class TaskDetailStrategy implements Strategy {

    @Override
    public String createSharding(String separator, String collectionId) {
        return new StringBuilder(StorageEnum.TASK_DETAIL.getType()).append(separator).append(collectionId).toString();
    }
}
