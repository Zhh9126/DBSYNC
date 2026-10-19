/**
 * DataSync Copyright 2026 All Rights Reserved.
 */
package org.dbdatasync.biz.impl;

import org.dbdatasync.biz.LineageService;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.model.Connector;
import org.dbdatasync.parser.model.FieldMapping;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.filter.Query;
import org.dbdatasync.sdk.model.LineageEdge;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.storage.StorageService;
import org.dbdatasync.storage.impl.SnowflakeIdWorker;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据血缘服务
 *
 * @author AE86
 * @version 1.0.0
 * @date 2026/8/21
 */
@Service
public class LineageServiceImpl implements LineageService {

    private static final String GRANULARITY_DATABASE = "DATABASE";
    private static final String GRANULARITY_TABLE = "TABLE";

    @Resource
    private StorageService storageService;

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Override
    public void collect(Mapping mapping) {
        if (null == mapping || StringUtil.isBlank(mapping.getId())) {
            return;
        }
        // 先清空旧血缘，再重采
        removeByMappingId(mapping.getId());

        String sourceConnectorId = mapping.getSourceConnectorId();
        String targetConnectorId = mapping.getTargetConnectorId();
        Connector sourceConnector = profileComponent.getConnector(sourceConnectorId);
        Connector targetConnector = profileComponent.getConnector(targetConnectorId);
        if (null == sourceConnector || null == targetConnector) {
            return;
        }
        String sourceType = sourceConnector.getConfig().getConnectorType();
        String targetType = targetConnector.getConfig().getConnectorType();

        List<Map> edges = new ArrayList<>();
        long now = System.currentTimeMillis();
        String mappingName = mapping.getName();

        // 库级血缘
        String sourceDatabase = mapping.getSourceDatabase();
        String targetDatabase = mapping.getTargetDatabase();
        if (StringUtil.isNotBlank(sourceDatabase) && StringUtil.isNotBlank(targetDatabase)) {
            edges.add(buildEdge(mapping.getId(), GRANULARITY_DATABASE, sourceConnectorId, targetConnectorId,
                    sourceDatabase, targetDatabase, sourceType, targetType, mappingName, now));
        }

        // 表级血缘：分页扫描任务下的表映射
        tableGroupProfile.pageScanTableGroups(mapping.getId(), ConfigConstant.PAGE_SIZE, groups -> {
            if (CollectionUtils.isEmpty(groups)) {
                return;
            }
            for (TableGroup group : groups) {
                Table sourceTable = group.getSourceTable();
                Table targetTable = group.getTargetTable();
                if (null == sourceTable || null == targetTable) {
                    continue;
                }
                String source = sourceTable.getName();
                String target = targetTable.getName();
                if (StringUtil.isBlank(source) || StringUtil.isBlank(target)) {
                    continue;
                }
                edges.add(buildEdge(mapping.getId(), GRANULARITY_TABLE, group.getSourceConnectorId(), group.getTargetConnectorId(),
                        source, target, sourceType, targetType, mappingName, now));
            }
        });

        if (!edges.isEmpty()) {
            storageService.addBatch(StorageEnum.LINEAGE, null, edges);
        }
    }

    @Override
    public void removeByMappingId(String mappingId) {
        if (StringUtil.isBlank(mappingId)) {
            return;
        }
        Query query = new Query();
        query.setType(StorageEnum.LINEAGE);
        query.addFilter(ConfigConstant.LINEAGE_MAPPING_ID, mappingId);
        storageService.delete(query);
    }

    @Override
    public List<FieldMapping> getFieldMappings(String mappingId, String sourceTable, String targetTable) {
        List<FieldMapping> result = new ArrayList<>();
        if (StringUtil.isBlank(mappingId) || StringUtil.isBlank(sourceTable) || StringUtil.isBlank(targetTable)) {
            return result;
        }
        final List<FieldMapping>[] holder = new List[]{result};
        tableGroupProfile.pageScanTableGroups(mappingId, ConfigConstant.PAGE_SIZE, groups -> {
            for (TableGroup g : groups) {
                if (g == null || g.getSourceTable() == null || g.getTargetTable() == null) {
                    continue;
                }
                if (!StringUtil.equals(g.getSourceTable().getName(), sourceTable)) {
                    continue;
                }
                if (!StringUtil.equals(g.getTargetTable().getName(), targetTable)) {
                    continue;
                }
                if (g.getFieldMapping() != null && !g.getFieldMapping().isEmpty()) {
                    holder[0].addAll(g.getFieldMapping());
                }
            }
        });
        return holder[0];
    }

    @Override
    public List<LineageEdge> queryAll() {
        List<LineageEdge> result = new ArrayList<>();
        Query query = new Query();
        query.setType(StorageEnum.LINEAGE);
        query.setPageNum(1);
        query.setPageSize(ConfigConstant.PAGE_SIZE);
        // 循环分页拉取全部
        while (true) {
            Paging paging = storageService.query(query);
            if (null == paging || CollectionUtils.isEmpty(paging.getData())) {
                break;
            }
            for (Object row : paging.getData()) {
                LineageEdge edge = JsonUtil.mapToObj((Map) row, LineageEdge.class);
                if (null != edge) {
                    result.add(edge);
                }
            }
            if (paging.getData().size() < query.getPageSize()) {
                break;
            }
            query.setPageNum(query.getPageNum() + 1);
        }
        return result;
    }

    private Map buildEdge(String mappingId, String granularity, String sourceConnectorId, String targetConnectorId,
                          String source, String target, String sourceType, String targetType, String mappingName, long now) {
        Map<String, Object> row = new HashMap<>();
        row.put(ConfigConstant.CONFIG_MODEL_ID, String.valueOf(snowflakeIdWorker.nextId()));
        row.put(ConfigConstant.CONFIG_MODEL_CREATE_TIME, now);
        row.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, now);
        row.put(ConfigConstant.LINEAGE_MAPPING_ID, mappingId);
        row.put(ConfigConstant.LINEAGE_GRANULARITY, granularity);
        row.put(ConfigConstant.LINEAGE_SOURCE_CONNECTOR_ID, sourceConnectorId);
        row.put(ConfigConstant.LINEAGE_TARGET_CONNECTOR_ID, targetConnectorId);
        row.put(ConfigConstant.LINEAGE_SOURCE, source);
        row.put(ConfigConstant.LINEAGE_TARGET, target);
        row.put(ConfigConstant.LINEAGE_SOURCE_TYPE, sourceType);
        row.put(ConfigConstant.LINEAGE_TARGET_TYPE, targetType);
        row.put(ConfigConstant.LINEAGE_MAPPING_NAME, mappingName);
        return row;
    }
}
