/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.sdk.storage.strategy;

import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.storage.Strategy;

/**
 * 任务执行结果(meta)：单表存储
 *
 * @Author AE86
 * @Version 1.0.0
 * @Date 2026-07-17 17:00
 */
public final class MetaStrategy implements Strategy {

    @Override
    public String createSharding(String separator, String collectionId) {
        return StorageEnum.META.getType();
    }
}
