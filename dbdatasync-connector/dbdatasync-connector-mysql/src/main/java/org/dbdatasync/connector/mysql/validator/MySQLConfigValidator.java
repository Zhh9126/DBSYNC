/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.connector.mysql.validator;

import org.dbdatasync.connector.mysql.constant.MySQLConstant;
import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.AbstractDataBaseConfigValidator;
import org.dbdatasync.sdk.connector.database.AbstractDatabaseConnector;

import java.util.Map;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2020-01-08 15:17
 */
public class MySQLConfigValidator extends AbstractDataBaseConfigValidator {

    @Override
    public void modify(AbstractDatabaseConnector connectorService, DatabaseConfig connectorConfig, Map<String, String> params) {
        super.modify(connectorService, connectorConfig, params);
        MySQLConstant.enrichJdbcProperties(connectorConfig);
    }
}
