/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.oceanbaseoracle.validator;

import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.AbstractDataBaseConfigValidator;
import org.dbdatasync.sdk.connector.database.AbstractDatabaseConnector;

import java.util.Map;

/**
 * OceanBase Oracle 模式连接配置校验
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-07-31 14:10
 */
public final class OceanBaseOracleConfigValidator extends AbstractDataBaseConfigValidator {

    private static final String DEFAULT_DRIVER = "com.oceanbase.jdbc.Driver";

    @Override
    public void modify(AbstractDatabaseConnector connectorService, DatabaseConfig connectorConfig, Map<String, String> params) {
        super.modify(connectorService, connectorConfig, params);
        if (StringUtil.isBlank(connectorConfig.getDriverClassName())) {
            connectorConfig.setDriverClassName(DEFAULT_DRIVER);
        }
        connectorConfig.setUrl(connectorService.buildJdbcUrl(connectorConfig, connectorConfig.getDatabase()));
    }
}
