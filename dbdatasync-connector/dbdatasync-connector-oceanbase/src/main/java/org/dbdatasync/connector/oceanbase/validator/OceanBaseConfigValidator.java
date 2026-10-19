/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.oceanbase.validator;

import org.dbdatasync.common.util.NumberUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.oceanbase.cdc.OceanBaseBinlogConfig;
import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.AbstractDataBaseConfigValidator;
import org.dbdatasync.sdk.connector.database.AbstractDatabaseConnector;

import org.springframework.util.Assert;

import java.util.Map;

/**
 * OceanBase 连接配置校验
 */
public class OceanBaseConfigValidator extends AbstractDataBaseConfigValidator {

    private static final String DEFAULT_DRIVER = "com.oceanbase.jdbc.Driver";

    @Override
    public void modify(AbstractDatabaseConnector connectorService, DatabaseConfig connectorConfig, Map<String, String> params) {
        super.modify(connectorService, connectorConfig, params);
        if (StringUtil.isBlank(connectorConfig.getDriverClassName())) {
            connectorConfig.setDriverClassName(DEFAULT_DRIVER);
        }
        putExtInfo(connectorConfig, OceanBaseBinlogConfig.BINLOG_HOST, params.get("binlogHost"));
        String binlogPort = params.get("binlogPort");
        if (StringUtil.isNotBlank(binlogPort)) {
            int port = NumberUtil.toInt(binlogPort.trim());
            Assert.isTrue(port > 0, "Binlog 端口无效");
            connectorConfig.getExtInfo().setProperty(OceanBaseBinlogConfig.BINLOG_PORT, String.valueOf(port));
        }
    }

    private void putExtInfo(DatabaseConfig config, String key, String value) {
        if (StringUtil.isNotBlank(value)) {
            config.getExtInfo().setProperty(key, value.trim());
        }
    }
}
