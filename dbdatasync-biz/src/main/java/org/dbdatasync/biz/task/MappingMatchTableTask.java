/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.task;

import org.dbdatasync.biz.RepeatedTableGroupException;
import org.dbdatasync.biz.TableGroupService;
import org.dbdatasync.common.dispatch.AbstractDispatchTask;
import org.dbdatasync.common.dispatch.DispatchTaskService;
import org.dbdatasync.common.enums.DispatchTaskEnum;
import org.dbdatasync.common.rsa.RsaManager;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.base.ConnectorFactory;
import org.dbdatasync.parser.ParserComponent;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.sdk.SdkException;
import org.dbdatasync.sdk.enums.TableTypeEnum;
import org.dbdatasync.sdk.model.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 异步匹配相似表，完成后提交驱动统计任务
 *
 * @author AE86
 * @version 1.0.0
 * @date 2026/4/8
 */
public class MappingMatchTableTask extends AbstractDispatchTask {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private String mappingId;

    private TableGroupService tableGroupService;

    private ProfileComponent profileComponent;

    private ParserComponent parserComponent;

    private TableGroupProfile tableGroupProfile;

    private ConnectorFactory connectorFactory;

    private RsaManager rsaManager;

    private DispatchTaskService dispatchTaskService;

    @Override
    public DispatchTaskEnum getType() {
        return DispatchTaskEnum.MAPPING_MATCH_TABLE;
    }

    @Override
    public String getUniqueId() {
        return mappingId;
    }

    @Override
    public void execute() {
        Mapping mapping = profileComponent.getMapping(mappingId);
        if (mapping == null) {
            logger.warn("Mapping not found, skip match table, mappingId={}", mappingId);
            return;
        }
        matchSimilarTableGroups(mapping);
        submitMappingCountTask(mapping);
    }

    private void matchSimilarTableGroups(Mapping mapping) {
        List<Table> sourceTables = mapping.getSourceTable();
        List<Table> targetTables = mapping.getTargetTable();
        if (CollectionUtils.isEmpty(sourceTables) || CollectionUtils.isEmpty(targetTables)) {
            return;
        }
        Map<String, Table> targetTableMap = targetTables.stream()
                .collect(Collectors.toMap(table -> table.getName().toUpperCase(), table -> table, (a, b) -> a));
        for (Table sourceTable : sourceTables) {
            if (StringUtil.isBlank(sourceTable.getName())) {
                continue;
            }
            targetTableMap.computeIfPresent(sourceTable.getName().toUpperCase(), (k, targetTable) -> {
                if (TableTypeEnum.isTable(targetTable.getType())) {
                    addTableGroup(mapping.getId(), sourceTable.getName(), targetTable.getName());
                }
                return targetTable;
            });
        }
    }

    private void addTableGroup(String mappingId, String sourceTableName, String targetTableName) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("mappingId", mappingId);
            params.put("sourceTable", sourceTableName);
            params.put("targetTable", targetTableName);
            params.put("sourceType", TableTypeEnum.TABLE.getCode());
            params.put("targetType", TableTypeEnum.TABLE.getCode());
            tableGroupService.add(params);
        } catch (RepeatedTableGroupException | SdkException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void submitMappingCountTask(Mapping mapping) {
        MappingCountTask task = new MappingCountTask();
        task.setMappingId(mapping.getId());
        task.setMetaSnapshot(null);
        task.setParserComponent(parserComponent);
        task.setProfileComponent(profileComponent);
        task.setTableGroupProfile(tableGroupProfile);
        task.setTableGroupService(tableGroupService);
        task.setConnectorFactory(connectorFactory);
        task.setRsaManager(rsaManager);
        dispatchTaskService.execute(task);
    }

    public void setMappingId(String mappingId) {
        this.mappingId = mappingId;
    }

    public void setTableGroupService(TableGroupService tableGroupService) {
        this.tableGroupService = tableGroupService;
    }

    public void setProfileComponent(ProfileComponent profileComponent) {
        this.profileComponent = profileComponent;
    }

    public void setParserComponent(ParserComponent parserComponent) {
        this.parserComponent = parserComponent;
    }

    public void setTableGroupProfile(TableGroupProfile tableGroupProfile) {
        this.tableGroupProfile = tableGroupProfile;
    }

    public void setConnectorFactory(ConnectorFactory connectorFactory) {
        this.connectorFactory = connectorFactory;
    }

    public void setRsaManager(RsaManager rsaManager) {
        this.rsaManager = rsaManager;
    }

    public void setDispatchTaskService(DispatchTaskService dispatchTaskService) {
        this.dispatchTaskService = dispatchTaskService;
    }
}
