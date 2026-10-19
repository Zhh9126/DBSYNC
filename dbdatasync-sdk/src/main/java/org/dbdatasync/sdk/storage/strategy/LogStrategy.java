/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.sdk.storage.strategy;

import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.storage.Strategy;

/**
 * 日志：Connector、Mapping、TableGroup、Meta、系统日志
 *
 * @Author AE86
 * @Version 1.0.0
 * @Date 2019-11-16 23:22
 */
public final class LogStrategy implements Strategy {

    @Override
    public String createSharding(String separator, String collectionId) {
        return StorageEnum.LOG.getType();
    }
}
