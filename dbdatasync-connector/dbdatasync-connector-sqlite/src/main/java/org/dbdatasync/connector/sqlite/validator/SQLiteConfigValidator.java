/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlite.validator;

import org.dbdatasync.common.util.NumberUtil;
import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.AbstractDataBaseConfigValidator;
import org.dbdatasync.sdk.connector.database.AbstractDatabaseConnector;
import org.dbdatasync.sdk.util.PropertiesUtil;

import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;

/**
 * SQLite连接配置校验器实现
 *
 * @Author bble
 * @Version 1.0.0
 * @Date 2023-11-28 16:22
 */
public final class SQLiteConfigValidator extends AbstractDataBaseConfigValidator {

    @Override
    public void modify(AbstractDatabaseConnector connectorService, DatabaseConfig connectorConfig, Map<String, String> params) {
        String properties = params.get("properties");
        String serviceName = params.get("serviceName");
        String driverClassName = params.get("driverClassName");
        int maxActive = NumberUtil.toInt(Objects.toString(params.get("maxActive")), connectorConfig.getMaxActive());
        long keepAlive = NumberUtil.toLong(Objects.toString(params.get("keepAlive")), connectorConfig.getKeepAlive());
        Assert.isTrue(maxActive >= 1 && maxActive <= 512, "最大连接数只允许输入1-512.");
        Assert.isTrue(keepAlive >= 10000 && keepAlive <= 120000, "有效期只允许输入10000-120000.");

        connectorConfig.setServiceName(serviceName);
        connectorConfig.setProperties(PropertiesUtil.parse(properties));
        connectorConfig.setUrl(connectorService.buildJdbcUrl(connectorConfig, serviceName));
        connectorConfig.setDriverClassName(driverClassName);
        connectorConfig.setMaxActive(maxActive);
        connectorConfig.setKeepAlive(keepAlive);
    }
}
