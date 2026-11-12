/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.kingbase.validator;

import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.kingbase.constant.KingbaseConfigConstant;
import org.dbdatasync.connector.postgresql.validator.PostgreSQLConfigValidator;
import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.database.AbstractDatabaseConnector;

import java.util.Map;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-06 18:30
 */
public final class KingbaseConfigValidator extends PostgreSQLConfigValidator {

    @Override
    public void modify(AbstractDatabaseConnector connectorService, DatabaseConfig connectorConfig, Map<String, String> params) {
        super.modify(connectorService, connectorConfig, params);
        if (StringUtil.isBlank(connectorConfig.getDriverClassName())) {
            connectorConfig.setDriverClassName("com.kingbase8.Driver");
        }
        String pluginName = params.get(KingbaseConfigConstant.PLUGIN_NAME);
        if (StringUtil.isBlank(pluginName)) {
            // 编辑/复制场景下表单可能不提交 pluginName，优先保留已还原的 extInfo
            pluginName = connectorConfig.getExtInfo().getProperty(KingbaseConfigConstant.PLUGIN_NAME);
        }
        if (StringUtil.isBlank(pluginName) || "pgoutput".equalsIgnoreCase(pluginName)) {
            // KES V9 内置逻辑解码插件为 syslogical_output，test_decoding 默认不存在
            pluginName = KingbaseConfigConstant.DEFAULT_PLUGIN;
        }
        connectorConfig.getExtInfo().put(KingbaseConfigConstant.PLUGIN_NAME, pluginName);
    }
}