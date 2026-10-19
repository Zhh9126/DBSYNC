/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.biz.task;

import org.dbdatasync.biz.TableGroupService;
import org.dbdatasync.common.dispatch.AbstractDispatchTask;
import org.dbdatasync.common.rsa.RsaManager;
import org.dbdatasync.connector.base.ConnectorFactory;
import org.dbdatasync.parser.ParserComponent;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.util.ConnectorInstanceUtil;
import org.dbdatasync.parser.util.PickerUtil;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.connector.DefaultMetaContext;
import org.dbdatasync.sdk.enums.ModelEnum;
import org.dbdatasync.sdk.model.ConnectorConfig;
import org.dbdatasync.sdk.spi.ConnectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Map;

/**
 * 抽象类统计驱动总数任务
 *
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2025-06-25 01:00
 */
public abstract class AbstractCountTask extends AbstractDispatchTask {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    protected String mappingId;

    protected ParserComponent parserComponent;

    protected ProfileComponent profileComponent;

    protected TableGroupProfile tableGroupProfile;

    protected TableGroupService tableGroupService;

    protected ConnectorFactory connectorFactory;

    private RsaManager rsaManager;

    public void setMappingId(String mappingId) {
        this.mappingId = mappingId;
    }

    public void setParserComponent(ParserComponent parserComponent) {
        this.parserComponent = parserComponent;
    }

    public void setProfileComponent(ProfileComponent profileComponent) {
        this.profileComponent = profileComponent;
    }

    public void setTableGroupProfile(TableGroupProfile tableGroupProfile) {
        this.tableGroupProfile = tableGroupProfile;
    }

    public void setTableGroupService(TableGroupService tableGroupService) {
        this.tableGroupService = tableGroupService;
    }

    public void setConnectorFactory(ConnectorFactory connectorFactory) {
        this.connectorFactory = connectorFactory;
    }

    public void setRsaManager(RsaManager rsaManager) {
        this.rsaManager = rsaManager;
    }

    protected void updateTableGroupCount(Mapping mapping, TableGroup tableGroup) {
        long now = Instant.now().toEpochMilli();
        TableGroup group = PickerUtil.mergeTableGroupConfig(mapping, tableGroup);
        Map<String, String> command = parserComponent.getCommand(mapping, group);
        String sourceConnectorId = mapping.getSourceConnectorId();
        String instanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mappingId, sourceConnectorId, ConnectorInstanceUtil.SOURCE_SUFFIX);
        ConnectorConfig config = profileComponent.getConnector(sourceConnectorId).getConfig();
        ConnectorInstance connectorInstance = connectorFactory.connect(instanceId);
        Assert.notNull(command, "command can not null");
        ConnectorService connectorService = connectorFactory.getConnectorService(config);

        DefaultMetaContext metaContext = new DefaultMetaContext();
        metaContext.setCommand(command);
        metaContext.setSourceTable(group.getSourceTable());
        metaContext.setSourceConnectorInstance(connectorInstance);
        setRsaConfig(metaContext);

        long count = connectorService.getCount(connectorInstance, metaContext);
        tableGroup.getSourceTable().setCount(count);
        profileComponent.editConfigModel(tableGroup);
        logger.info("{}表{}, 总数:{}, {}ms", mapping.getName(), tableGroup.getSourceTable().getName(), count, (Instant.now().toEpochMilli() - now));
    }

    protected boolean shouldStop(Mapping mapping) {
        return !isRunning() || !ModelEnum.isFull(mapping.getModel());
    }

    private void setRsaConfig(DefaultMetaContext context) {
        if (profileComponent.getSystemConfig().isEnableOpenAPI()) {
            context.setRsaManager(rsaManager);
            context.setRsaConfig(profileComponent.getSystemConfig().getRsaConfig());
        }
    }
}
