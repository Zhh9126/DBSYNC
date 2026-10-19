/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.sdk.storage.strategy;

import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.storage.Strategy;

/**
 * 表映射关系配置：单表存储
 *
 * @Author AE86
 * @Version 1.0.0
 * @Date 2026-07-17 17:00
 */
public final class TableGroupStrategy implements Strategy {

    @Override
    public String createSharding(String separator, String collectionId) {
        return StorageEnum.TABLE_GROUP.getType();
    }
}
