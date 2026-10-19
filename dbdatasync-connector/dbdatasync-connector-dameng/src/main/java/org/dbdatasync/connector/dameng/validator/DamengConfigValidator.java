/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.dameng.validator;

import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.dameng.constant.DamengConstant;
import org.dbdatasync.connector.oracle.validator.OracleConfigValidator;
import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.database.AbstractDatabaseConnector;

import java.util.Map;
import java.util.Properties;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-07 02:00
 */
public final class DamengConfigValidator extends OracleConfigValidator {

    @Override
    public void modify(AbstractDatabaseConnector connectorService, DatabaseConfig connectorConfig, Map<String, String> params) {
        super.modify(connectorService, connectorConfig, params);
        if (StringUtil.isBlank(connectorConfig.getDriverClassName())) {
            connectorConfig.setDriverClassName("dm.jdbc.driver.DmDriver");
        }
        DamengConstant.enrichJdbcProperties(connectorConfig);
        applySchemaProperty(connectorConfig, params.get("schema"));
        connectorConfig.setUrl(connectorService.buildJdbcUrl(connectorConfig, connectorConfig.getDatabase()));
    }

    /**
     * 达梦通过连接属性 {@code schema} 指定当前模式；未填写时驱动默认使用与用户名同名的模式。
     */
    private void applySchemaProperty(DatabaseConfig connectorConfig, String schema) {
        if (StringUtil.isBlank(schema)) {
            return;
        }
        Properties properties = connectorConfig.getProperties();
        if (properties == null) {
            properties = new Properties();
            connectorConfig.setProperties(properties);
        }
        properties.setProperty("schema", schema.trim());
    }
}
