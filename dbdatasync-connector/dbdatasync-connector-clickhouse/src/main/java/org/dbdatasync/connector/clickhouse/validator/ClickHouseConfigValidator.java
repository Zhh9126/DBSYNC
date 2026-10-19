/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.clickhouse.validator;

import org.dbdatasync.common.util.NumberUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.clickhouse.constant.ClickHouseConfigConstant;
import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.AbstractDataBaseConfigValidator;
import org.dbdatasync.sdk.connector.database.AbstractDatabaseConnector;

import java.util.Map;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-05-29 23:50
 */
public final class ClickHouseConfigValidator extends AbstractDataBaseConfigValidator {

    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_POLL_BATCH_SIZE = 500;

    @Override
    public void modify(AbstractDatabaseConnector connectorService, DatabaseConfig connectorConfig, Map<String, String> params) {
        super.modify(connectorService, connectorConfig, params);
        int pollInterval = NumberUtil.toInt(params.get(ClickHouseConfigConstant.POLL_INTERVAL_SECONDS), DEFAULT_POLL_INTERVAL_SECONDS);
        if (pollInterval < 1) {
            pollInterval = DEFAULT_POLL_INTERVAL_SECONDS;
        }
        int pollBatchSize = NumberUtil.toInt(params.get(ClickHouseConfigConstant.POLL_BATCH_SIZE), DEFAULT_POLL_BATCH_SIZE);
        if (pollBatchSize < 1) {
            pollBatchSize = DEFAULT_POLL_BATCH_SIZE;
        }
        connectorConfig.getExtInfo().put(ClickHouseConfigConstant.POLL_INTERVAL_SECONDS, String.valueOf(pollInterval));
        connectorConfig.getExtInfo().put(ClickHouseConfigConstant.POLL_BATCH_SIZE, String.valueOf(pollBatchSize));
        if (StringUtil.isBlank(connectorConfig.getDriverClassName())) {
            connectorConfig.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        }
    }
}
