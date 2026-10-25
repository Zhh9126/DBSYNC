/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.tidb;

import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.connector.mysql.MySQLConnector;
import org.dbdatasync.connector.tidb.constant.TiDBConstant;
import org.dbdatasync.connector.tidb.schema.TiDBSchemaResolver;
import org.dbdatasync.connector.tidb.validator.TiDBConfigValidator;
import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.ConfigValidator;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.connector.ConnectorServiceContext;
import org.dbdatasync.sdk.connector.database.DatabaseConnectorInstance;
import org.dbdatasync.sdk.enums.ListenerTypeEnum;
import org.dbdatasync.sdk.listener.DatabaseQuartzListener;
import org.dbdatasync.sdk.listener.Listener;
import org.dbdatasync.sdk.schema.SchemaResolver;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TiDB 连接器（兼容 MySQL 协议）
 *
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-07 00:10
 */
public final class TiDBConnector extends MySQLConnector {

    private static final Set<String> SYSTEM_DATABASES = Stream.of(
            "information_schema", "mysql", "performance_schema", "sys", "metrics_schema")
            .collect(Collectors.toSet());

    private final TiDBConfigValidator configValidator = new TiDBConfigValidator();
    private final TiDBSchemaResolver schemaResolver = new TiDBSchemaResolver();

    @Override
    public String getConnectorType() {
        return "TiDB";
    }

    @Override
    public ConfigValidator getConfigValidator() {
        return configValidator;
    }

    @Override
    public SchemaResolver getSchemaResolver() {
        return schemaResolver;
    }

    @Override
    public ConnectorInstance connect(DatabaseConfig config, ConnectorServiceContext context) {
        TiDBConstant.enrichJdbcProperties(config);
        return super.connect(config, context);
    }

    /**
     * TiDB 5.0+ 不再兼容 MySQL binlog 协议(官方增量通道为 TiCDC), 故仅提供定时轮询增量:
     * 基于时间戳/自增列的 {@link DatabaseQuartzListener} 周期抽取。
     */
    @Override
    public Listener getListener(String listenerType) {
        if (ListenerTypeEnum.isTiming(listenerType)) {
            return new DatabaseQuartzListener();
        }
        return null;
    }

    @Override
    public List<String> getDatabases(DatabaseConnectorInstance connectorInstance) {
        return connectorInstance.execute(databaseTemplate -> {
            List<String> databases = databaseTemplate.queryForList("SHOW DATABASES", String.class);
            if (CollectionUtils.isEmpty(databases)) {
                return Collections.emptyList();
            }
            return databases.stream()
                    .filter(name -> !SYSTEM_DATABASES.contains(name.toLowerCase()))
                    .collect(Collectors.toList());
        });
    }
}
