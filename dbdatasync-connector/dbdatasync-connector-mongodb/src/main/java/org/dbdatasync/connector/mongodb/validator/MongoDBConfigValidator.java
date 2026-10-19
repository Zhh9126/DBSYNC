/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.mongodb.validator;

import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.common.util.NumberUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.mongodb.MongoDBConnector;
import org.dbdatasync.connector.mongodb.config.MongoDBConfig;
import org.dbdatasync.connector.mongodb.util.MongoUtil;
import org.dbdatasync.sdk.connector.ConfigValidator;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.util.PropertiesUtil;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-06 20:00
 */
public final class MongoDBConfigValidator implements ConfigValidator<MongoDBConnector, MongoDBConfig> {

    @Override
    public void modify(MongoDBConnector connectorService, MongoDBConfig connectorConfig, Map<String, String> params) {
        String host = params.get("host");
        int port = NumberUtil.toInt(Objects.toString(params.get("port")));
        String username = params.get("username");
        String password = params.get("password");
        String database = params.get("database");
        String authDatabase = params.get("authDatabase");
        String properties = params.get("properties");
        int maxPoolSize = NumberUtil.toInt(Objects.toString(params.get("maxPoolSize")), connectorConfig.getMaxPoolSize());
        Assert.hasText(host, "Host is empty.");
        Assert.isTrue(port > 0, "Port is invalid.");
        Assert.hasText(database, "Database is empty.");
        Assert.isTrue(maxPoolSize >= 1 && maxPoolSize <= 512, "最大连接数只允许输入1-512.");

        connectorConfig.setHost(host);
        connectorConfig.setPort(port);
        connectorConfig.setUsername(username);
        connectorConfig.setPassword(password);
        connectorConfig.setDatabase(database);
        connectorConfig.setAuthDatabase(StringUtil.isNotBlank(authDatabase) ? authDatabase : "admin");
        connectorConfig.setMaxPoolSize(maxPoolSize);
        connectorConfig.setProperties(PropertiesUtil.parse(properties));
        connectorConfig.setUrl(MongoUtil.buildConnectionString(connectorConfig));
    }

    @Override
    public Table modifyExtendedTable(MongoDBConnector connectorService, Map<String, String> params) {
        Table table = new Table();
        String tableName = params.get("tableName");
        String columnList = params.get("columnList");
        Assert.hasText(tableName, "TableName is empty");
        Assert.hasText(columnList, "ColumnList is empty");
        List<Field> fields = JsonUtil.jsonToArray(columnList, Field.class);
        Assert.notEmpty(fields, "字段不能为空.");
        table.setName(tableName);
        table.setColumn(fields);
        table.setType(connectorService.getExtendedTableType().getCode());
        return table;
    }
}
