/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.kingbase;

import org.dbdatasync.connector.kingbase.cdc.KingbaseListener;
import org.dbdatasync.connector.kingbase.schema.KingbaseSchemaResolver;
import org.dbdatasync.connector.kingbase.validator.KingbaseConfigValidator;
import org.dbdatasync.connector.postgresql.PostgreSQLConnector;
import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.ConfigValidator;
import org.dbdatasync.sdk.enums.ListenerTypeEnum;
import org.dbdatasync.sdk.listener.DatabaseQuartzListener;
import org.dbdatasync.sdk.listener.Listener;
import org.dbdatasync.sdk.schema.SchemaResolver;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-06 18:30
 */
public final class KingbaseConnector extends PostgreSQLConnector {

    private final KingbaseConfigValidator configValidator = new KingbaseConfigValidator();
    private final KingbaseSchemaResolver schemaResolver = new KingbaseSchemaResolver();

    @Override
    public String getConnectorType() {
        return "Kingbase";
    }

    @Override
    public ConfigValidator getConfigValidator() {
        return configValidator;
    }

    @Override
    public Listener getListener(String listenerType) {
        if (ListenerTypeEnum.isTiming(listenerType)) {
            return new DatabaseQuartzListener();
        }
        if (ListenerTypeEnum.isLog(listenerType)) {
            return new KingbaseListener();
        }
        return null;
    }

    @Override
    public SchemaResolver getSchemaResolver() {
        return schemaResolver;
    }

    @Override
    public String buildJdbcUrl(DatabaseConfig config, String database) {
        StringBuilder url = new StringBuilder();
        url.append("jdbc:kingbase8://").append(config.getHost()).append(":").append(config.getPort()).append("/");
        if (org.dbdatasync.common.util.StringUtil.isNotBlank(database)) {
            url.append(database);
        }
        return url.toString();
    }
}
