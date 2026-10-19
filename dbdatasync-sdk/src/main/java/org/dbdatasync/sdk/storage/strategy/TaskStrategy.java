/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.sdk.storage.strategy;

import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.storage.Strategy;

/**
 * @Author wuji
 * @Version 1.0.0
 * @Date 2025-10-18 20:36
 */
public final class TaskStrategy implements Strategy {

    @Override
    public String createSharding(String separator, String collectionId) {
        return StorageEnum.TASK.getType();
    }
}
