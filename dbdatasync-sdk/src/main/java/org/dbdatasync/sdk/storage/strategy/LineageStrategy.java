/**
 * DataSync Copyright 2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.storage.strategy;

import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.storage.Strategy;

/**
 * 数据血缘：单表存储
 *
 * @author AE86
 * @version 1.0.0
 * @date 2026/8/21
 */
public final class LineageStrategy implements Strategy {

    @Override
    public String createSharding(String separator, String collectionId) {
        return StorageEnum.LINEAGE.getType();
    }
}
