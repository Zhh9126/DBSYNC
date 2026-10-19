/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.connector;

import org.dbdatasync.common.model.RsaConfig;
import org.dbdatasync.common.rsa.RsaManager;
import org.dbdatasync.sdk.filter.BooleanFilter;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.plugin.CustomContext;
import org.dbdatasync.sdk.plugin.MetaContext;

import java.util.Map;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-02-12 00:46
 */
public class DefaultMetaContext implements MetaContext, CustomContext {

    private BooleanFilter booleanFilter = new BooleanFilter();
    private boolean targetConnector = false;
    private Table sourceTable;
    private Map<String, String> command;
    private ConnectorInstance sourceConnectorInstance;
    private RsaManager rsaManager;
    private RsaConfig rsaConfig;

    @Override
    public Table getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(Table sourceTable) {
        this.sourceTable = sourceTable;
    }

    @Override
    public Map<String, String> getCommand() {
        return command;
    }

    @Override
    public void setCommand(Map<String, String> command) {
        this.command = command;
    }

    @Override
    public ConnectorInstance getSourceConnectorInstance() {
        return sourceConnectorInstance;
    }

    @Override
    public void setSourceConnectorInstance(ConnectorInstance sourceConnectorInstance) {
        this.sourceConnectorInstance = sourceConnectorInstance;
    }

    @Override
    public RsaManager getRsaManager() {
        return rsaManager;
    }

    public void setRsaManager(RsaManager rsaManager) {
        this.rsaManager = rsaManager;
    }

    @Override
    public RsaConfig getRsaConfig() {
        return rsaConfig;
    }

    public void setRsaConfig(RsaConfig rsaConfig) {
        this.rsaConfig = rsaConfig;
    }

    @Override
    public boolean isTargetConnector() {
        return targetConnector;
    }

    @Override
    public BooleanFilter getFilter() {
        return booleanFilter;
    }

    public BooleanFilter getBooleanFilter() {
        return booleanFilter;
    }

    public void setBooleanFilter(BooleanFilter booleanFilter) {
        this.booleanFilter = booleanFilter;
    }

    public void setTargetConnector(boolean targetConnector) {
        this.targetConnector = targetConnector;
    }
}
