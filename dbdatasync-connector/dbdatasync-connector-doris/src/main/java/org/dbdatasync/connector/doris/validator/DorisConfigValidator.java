/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.doris.validator;

import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.doris.constant.DorisConstant;
import org.dbdatasync.connector.mysql.validator.MySQLConfigValidator;
import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.database.AbstractDatabaseConnector;

import java.util.Map;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-06 21:00
 */
public final class DorisConfigValidator extends MySQLConfigValidator {

    @Override
    public void modify(AbstractDatabaseConnector connectorService, DatabaseConfig connectorConfig, Map<String, String> params) {
        super.modify(connectorService, connectorConfig, params);
        if (StringUtil.isBlank(connectorConfig.getDriverClassName())) {
            connectorConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        }
        DorisConstant.enrichJdbcProperties(connectorConfig);
        DorisConstant.enrichExtInfo(connectorConfig, params.get(DorisConstant.HTTP_PORT), params.get(DorisConstant.WRITE_MODE));
    }
}
