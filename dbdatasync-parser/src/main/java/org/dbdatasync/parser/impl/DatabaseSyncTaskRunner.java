/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.parser.impl;

import org.dbdatasync.common.enums.CommonTaskStatusEnum;
import org.dbdatasync.common.enums.TaskLevelEnum;
import org.dbdatasync.common.model.Result;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.base.ConnectorFactory;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.ParserComponent;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.model.Connector;
import org.dbdatasync.parser.model.FieldMapping;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.model.Task;
import org.dbdatasync.parser.util.ConnectorInstanceUtil;
import org.dbdatasync.parser.util.ConnectorServiceContextUtil;
import org.dbdatasync.parser.util.DatabaseSyncMappingUtil;
import org.dbdatasync.sdk.config.DDLConfig;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.connector.database.Database;
import org.dbdatasync.sdk.connector.database.DatabaseConnectorInstance;
import org.dbdatasync.sdk.connector.DefaultConnectorServiceContext;
import org.dbdatasync.sdk.enums.DatabaseMigrationDetailTypeEnum;
import org.dbdatasync.sdk.enums.TableTypeEnum;
import org.dbdatasync.sdk.model.CommonTaskSnapshot;
import org.dbdatasync.sdk.model.DatabaseMapping;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.MetaInfo;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.util.TaskSnapshotUtil;
import org.dbdatasync.storage.impl.SnowflakeIdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 整库迁移执行引擎：复用映射通道的全量链路（ParserComponent.execute）逐表迁移数据，
 * 进度/状态写入任务级 Meta 与表级明细 Meta（权威在 DBDataSync_meta，供列表页计算进度）。
 * <p>enableCopyData（数据迁移）+ enableCopySchema（结构迁移）：
 * 目标库/表缺失时，若两端连接器具备 DDL 能力（sdk Database 接口），
 * 自动「建库 → 建表（源表 DDL → 目标方言）」，overwriteSchema 先删后建；
 * 无 DDL 能力的连接器目标端仍按缺失失败并跳过。
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-10-17
 */
@Service
public class DatabaseSyncTaskRunner {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private ParserComponent parserComponent;

    @Resource
    private ConnectorFactory connectorFactory;

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    /**
     * 运行中的整库迁移任务（taskId → 运行态 Task，用于 stop 中止与防重入）。
     */
    private final Map<String, Task> runningTasks = new ConcurrentHashMap<>();

    public boolean isRunning(String taskId) {
        return runningTasks.containsKey(taskId);
    }

    public boolean tryAcquire(String taskId) {
        return runningTasks.putIfAbsent(taskId, new Task(taskId)) == null;
    }

    public void release(String taskId) {
        runningTasks.remove(taskId);
    }

    public void stop(String taskId) {
        Task task = runningTasks.get(taskId);
        if (task != null) {
            task.stop();
        }
    }

    public void run(DatabaseSyncTask task) {
        String taskId = task.getId();
        Task rootTask = runningTasks.get(taskId);
        if (rootTask == null) {
            rootTask = new Task(taskId);
            runningTasks.put(taskId, rootTask);
        }
        // 任务/表级子任务共享启停状态（中止后不再发起新表迁移）
        boolean allOk = true;
        try {
            // 1、任务级 Meta（计数/状态权威，缺失则补建）
            Meta taskMeta = ensureTaskMeta(taskId, rootTask);
            // 2、表映射（按 index 升序）
            List<TableGroup> tableGroups = loadTableGroups(taskId);
            if (CollectionUtils.isEmpty(tableGroups)) {
                logger.warn("整库迁移任务 [{}] 无表映射，跳过执行", taskId);
                finishTaskMeta(taskMeta, true);
                return;
            }
            // 新表自动发现（enableAutoDiscover）：源端新增表 → 自动建表（可选）+ 追加表映射 + 参与本轮迁移
            if (task.isEnableAutoDiscover()) {
                try {
                    List<TableGroup> discovered = discoverNewTables(task, tableGroups);
                    if (!discovered.isEmpty()) {
                        logger.info("整库迁移任务 [{}] 自动发现源端新表: {} 张, 已参与本轮迁移", taskId, discovered.size());
                        tableGroups.addAll(discovered);
                    }
                } catch (Exception e) {
                    logger.error("整库迁移任务 [{}] 新表自动发现失败（不阻断既有映射迁移）: {}", taskId, e.getMessage(), e);
                }
            }
            // 3、库映射 index（写库级状态摘要）
            Map<String, Integer> mappingIndexByKey = buildMappingIndex(task);
            // 4、按 同源同库 簇迁移：同簇共享连接器实例与目标端表元数据
            Map<String, List<TableGroup>> clusters = new LinkedHashMap<>();
            for (TableGroup group : tableGroups) {
                clusters.computeIfAbsent(buildSourceTargetKey(group), k -> new ArrayList<>()).add(group);
            }
            taskMeta.setState(CommonTaskStatusEnum.RUNNING.getCode());
            metaProfile.updateMeta(taskMeta);
            for (Map.Entry<String, List<TableGroup>> cluster : clusters.entrySet()) {
                if (!rootTask.isRunning()) {
                    allOk = false;
                    break;
                }
                List<TableGroup> clusterGroups = cluster.getValue();
                // 建立源/目标连接器实例（连接失败整簇标记失败）
                try {
                    connectInstances(buildStubMapping(task, clusterGroups.get(0)));
                } catch (Exception e) {
                    allOk = false;
                    logger.error("整库迁移任务 [{}] 建立连接器实例失败: {}", taskId, e.getMessage(), e);
                    for (TableGroup group : clusterGroups) {
                        markGroupResult(taskId, group, mappingIndexByKey, false, e.getMessage());
                    }
                    continue;
                }
                // 结构迁移：先确保目标库存在（连接器 Druid 池在目录缺失时会静默回退 URL 默认库，
                // 必须先建库，再读取目标端元数据，否则元数据/写入会落到错误的默认库）
                if (task.isEnableCopySchema()) {
                    try {
                        ensureTargetDatabase(buildStubMapping(task, clusterGroups.get(0)));
                    } catch (Exception e) {
                        allOk = false;
                        logger.error("整库迁移任务 [{}] 自动建目标库失败: {}", taskId, e.getMessage(), e);
                        for (TableGroup group : clusterGroups) {
                            markGroupResult(taskId, group, mappingIndexByKey, false, e.getMessage());
                        }
                        continue;
                    }
                }
                // 目标端库表元数据预读（目标表缺失时由 syncOneTable 内部自动建表补齐）
                Map<String, Table> targetTableMetas;
                try {
                    targetTableMetas = loadTargetTableMetas(taskId, clusterGroups);
                } catch (Exception e) {
                    allOk = false;
                    logger.error("整库迁移任务 [{}] 读取目标端表元数据失败: {}", taskId, e.getMessage(), e);
                    for (TableGroup group : clusterGroups) {
                        markGroupResult(taskId, group, mappingIndexByKey, false, e.getMessage());
                    }
                    continue;
                }
                // 5、逐表执行结构校验 + 数据迁移
                for (TableGroup group : clusterGroups) {
                    if (!rootTask.isRunning()) {
                        allOk = false;
                        break;
                    }
                    try {
                        syncOneTable(task, rootTask, group, targetTableMetas);
                        markGroupResult(taskId, group, mappingIndexByKey, true, null);
                    } catch (Exception e) {
                        allOk = false;
                        logger.error("整库迁移表失败: {} -> {}, {}", safeName(group, true), safeName(group, false), e.getMessage(), e);
                        markGroupResult(taskId, group, mappingIndexByKey, false, e.getMessage());
                    }
                }
            }
            // 6、收尾：任务级状态（正常跑完=DONE，中止/有失败=READY 待重跑）
            finishTaskMeta(taskMeta, allOk && rootTask.isRunning());
            logger.info("整库迁移任务 [{}] 执行结束: {}", taskId, allOk ? "完成" : "存在失败/中止");
        } catch (Exception e) {
            logger.error("整库迁移任务 [{}] 执行异常: {}", taskId, e.getMessage(), e);
            allOk = false;
        } finally {
            runningTasks.remove(taskId);
        }
    }

    /**
     * 单表迁移：定位目标表元数据 → 字段映射 → 执行命令 → Parser 全量链路写入。
     */
    private void syncOneTable(DatabaseSyncTask task, Task rootTask, TableGroup group, Map<String, Table> targetTableMetas) {
        Mapping stub = buildStubMapping(task, group);
        connectInstances(stub);
        Table targetMeta = targetTableMetas.get(buildTargetTableKey(group));
        // 元数据为空列视为“表不存在”（getMetaInfo 对缺失表也会返回空列占位，不能据此认为表已存在）
        boolean targetMissing = targetMeta == null || CollectionUtils.isEmpty(targetMeta.getColumn());
        if (targetMissing) {
            if (!task.isEnableCopySchema()) {
                throw new IllegalStateException(String.format("目标表 [%s] 在目标库中不存在，请先建表或开启结构迁移（自动建表）",
                        safeName(group, false)));
            }
            // 结构迁移：目标库/表缺失时自动建库建表（两端连接器具备 DDL 能力时）
            targetMeta = autoCreateTargetTable(stub, group, task);
            targetTableMetas.put(buildTargetTableKey(group), targetMeta);
        }
        group.setFieldMapping(buildFieldMapping(group, targetMeta));
        group.setCommand(parserComponent.getCommand(stub, group));
        if (CollectionUtils.isEmpty(group.getCommand())) {
            throw new IllegalStateException(String.format("表 [%s] >> [%s] 执行命令构建失败", safeName(group, true), safeName(group, false)));
        }
        // 表级子任务：共享启停；写入走同步执行器（与映射通道 SYNC 模式一致，逐批串行写）
        Task tableTask = rootTask.createTableTask(group.getId());
        Executor writeExecutor = Runnable::run;
        parserComponent.execute(tableTask, stub, group, writeExecutor);
    }

    /**
     * 自动建库/建表（结构迁移 enableCopySchema）：
     * 1、目标库不存在 → 按目标连接器方言 CREATE DATABASE；
     * 2、overwriteSchema（覆盖结构）→ DROP 目标表再重建；
     * 3、读取源表 DDL，经目标连接器方言转换后 CREATE TABLE；
     * 4、回读目标表元数据返回（供字段映射构建）。
     * 依赖 sdk Database 接口，能力按连接器动态判定，不写死数据库类型。
     */
    private Table autoCreateTargetTable(Mapping stub, TableGroup group, DatabaseSyncTask task) {
        String targetTableName = group.getTargetTable() != null ? group.getTargetTable().getName() : StringUtil.EMPTY;
        Assert.hasText(targetTableName, "目标表名为空，无法自动建表");

        ConnectorInstance sourceInstance = connectorFactory.connect(
                ConnectorInstanceUtil.buildConnectorInstanceId(stub.getId(), stub.getSourceConnectorId(), "S"));
        ConnectorInstance targetInstance = connectorFactory.connect(
                ConnectorInstanceUtil.buildConnectorInstanceId(stub.getId(), stub.getTargetConnectorId(), "T"));
        Assert.notNull(sourceInstance, "源连接器实例不存在");
        Assert.notNull(targetInstance, "目标连接器实例不存在");

        Database targetDatabaseConnector;
        try {
            targetDatabaseConnector = (Database) connectorFactory.getConnectorService(targetInstance.getConfig());
        } catch (ClassCastException e) {
            throw new IllegalStateException(String.format("目标连接器 [%s] 不支持自动建表（缺少 DDL 能力），请先手动建表",
                    stub.getTargetConnectorId()));
        }

        // 1、目标库不存在 → 建库
        String targetDatabaseName = stub.getTargetDatabase();
        String targetSchemaName = stub.getTargetSchema();
        if (StringUtil.isNotBlank(targetDatabaseName) || StringUtil.isNotBlank(targetSchemaName)) {
            boolean exists = false;
            try {
                exists = targetDatabaseConnector.databaseExists((DatabaseConnectorInstance) targetInstance,
                        targetDatabaseName, targetSchemaName);
            } catch (Exception e) {
                logger.debug("目标库存在性检查失败（按不存在处理）: {}", e.getMessage());
            }
            if (!exists) {
                String createDatabase = targetDatabaseConnector.buildCreateDatabaseSql(targetDatabaseName, targetSchemaName);
                executeDDL(targetInstance, createDatabase);
                logger.info("自动创建目标库: {}", StringUtil.isNotBlank(targetDatabaseName) ? targetDatabaseName : targetSchemaName);
            }
        }

        // 2、覆盖结构 → 删除目标表（重建由后续 CREATE TABLE 完成）
        if (task.isOverwriteSchema()) {
            String dropTable = targetDatabaseConnector.buildDropTableSql((DatabaseConnectorInstance) targetInstance, targetTableName);
            executeDDL(targetInstance, dropTable);
            logger.info("覆盖结构：目标表已删除待重建: {}", targetTableName);
        }

        // 3、源表 DDL → 目标方言 CREATE TABLE
        String sourceDDL = null;
        Object sourceService = connectorFactory.getConnectorService(sourceInstance.getConfig());
        if (sourceService instanceof Database) {
            sourceDDL = ((Database) sourceService).getSourceTableDDL(
                    (DatabaseConnectorInstance) sourceInstance, group.getSourceTable().getName());
        }
        if (StringUtil.isBlank(sourceDDL)) {
            throw new IllegalStateException(String.format("自动建表失败：无法读取源表 [%s] 结构 DDL（源连接器不支持），请先手动建目标表",
                    safeName(group, true)));
        }
        String createTableSql = targetDatabaseConnector.getTargetTableDDL(
                (DatabaseConnectorInstance) targetInstance, targetTableName, sourceDDL);
        executeDDL(targetInstance, createTableSql);
        logger.info("自动创建目标表: {} -> {}", safeName(group, true), targetTableName);

        // 4、回读目标表元数据（供字段映射与命令构建），空列仍视为创建失败
        Table created = loadTargetTableMetas(stub.getId(), Collections.singletonList(group)).get(buildTargetTableKey(group));
        if (created == null || CollectionUtils.isEmpty(created.getColumn())) {
            throw new IllegalStateException(String.format("目标表 [%s] 创建后仍无法读取元数据，请检查目标连接器", targetTableName));
        }
        return created;
    }

    /**
     * 通过连接器统一 DDL 通道执行建库/建表语句（自动带防双向同步标识）。
     */
    private void executeDDL(ConnectorInstance targetInstance, String sql) {
        if (StringUtil.isBlank(sql)) {
            return;
        }
        DDLConfig config = new DDLConfig();
        config.setSql(sql);
        Result result = connectorFactory.writerDDL(targetInstance, config);
        if (result != null && result.getError() != null && result.getError().length() > 0) {
            throw new IllegalStateException("自动建库/建表 DDL 执行失败: " + result.getError());
        }
    }

    /**
     * 新表自动发现（enableAutoDiscover）：
     * 按每个「源连接库 -> 目标连接库」映射簇扫描源端表清单，
     * 与本任务既有 TableGroup（源表名）比对，差集即为源端新表。
     * 新表：读源表元数据 → 追加 TableGroup（库维字段继承映射、表名同名引用）→ 持久化后参与本轮迁移；
     * 自定义 SQL 视图与无法读取元数据的对象跳过（发现不阻断迁移）。
     */
    private List<TableGroup> discoverNewTables(DatabaseSyncTask task, List<TableGroup> existing) {
        List<TableGroup> created = new ArrayList<>();
        List<DatabaseMapping> mappings = DatabaseSyncMappingUtil.sortByIndex(task.getDatabaseMappings());
        if (CollectionUtils.isEmpty(mappings)) {
            return created;
        }
        // 已注册的 源表 按 簇键 维度登记（小写比对）
        Set<String> knownTablesByKey = new HashSet<>();
        int maxIndex = 0;
        for (TableGroup group : existing) {
            knownTablesByKey.add(buildSourceTargetKey(group) + "::"
                    + (group.getSourceTable() != null ? group.getSourceTable().getName().toLowerCase() : ""));
            maxIndex = Math.max(maxIndex, group.getIndex());
        }
        long now = System.currentTimeMillis();
        for (DatabaseMapping mapping : mappings) {
            if (mapping == null) {
                continue;
            }
            String clusterKey = StringUtil.join(Arrays.asList(
                    mapping.getSourceConnectorId(), mapping.getSourceDatabase(), mapping.getSourceSchema(),
                    mapping.getTargetConnectorId(), mapping.getTargetDatabase(), mapping.getTargetSchema()), "|");
            try {
                String sourceInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(task.getId(), mapping.getSourceConnectorId(), "S");
                ConnectorInstance sourceInstance = connectorFactory.connect(sourceInstanceId);
                if (sourceInstance == null) {
                    continue;
                }
                DefaultConnectorServiceContext ctx = ConnectorServiceContextUtil.buildConnectorServiceContext(
                        task.getId(), mapping.getSourceConnectorId(), mapping.getSourceDatabase(), mapping.getSourceSchema(),
                        mapping.getTargetConnectorId(), mapping.getTargetDatabase(), mapping.getTargetSchema(), true);
                List<Table> tables = connectorFactory.getTables(sourceInstance, (org.dbdatasync.sdk.connector.ConnectorServiceContext) ctx);
                if (CollectionUtils.isEmpty(tables)) {
                    continue;
                }
                List<String> candidateNames = new ArrayList<>();
                Set<String> candidateLower = new LinkedHashSet<>();
                for (Table table : tables) {
                    if (table == null || StringUtil.isBlank(table.getName())) {
                        continue;
                    }
                    // 普通表才参与发现（视图/物化视图跳过）
                    String type = StringUtil.trimToEmpty(table.getType()).toUpperCase();
                    if (type.contains("VIEW")) {
                        continue;
                    }
                    String lower = table.getName().toLowerCase();
                    if (knownTablesByKey.contains(clusterKey + "::" + lower) || !candidateLower.add(lower)) {
                        continue;
                    }
                    candidateNames.add(table.getName());
                }
                if (candidateNames.isEmpty()) {
                    continue;
                }
                Map<String, Table> sourceMetaMap = loadSourceTableMetaMap(task.getId(), mapping, candidateNames);
                for (String name : candidateNames) {
                    Table sourceTable = sourceMetaMap.get(name);
                    if (sourceTable == null) {
                        continue;
                    }
                    TableGroup group = new TableGroup();
                    group.setId(String.valueOf(snowflakeIdWorker.nextId()));
                    group.setTaskId(task.getId());
                    group.setIndex(++maxIndex);
                    group.setSourceConnectorId(mapping.getSourceConnectorId());
                    group.setTargetConnectorId(mapping.getTargetConnectorId());
                    group.setSourceDatabase(StringUtil.getIfBlank(mapping.getSourceDatabase(), ""));
                    group.setTargetDatabase(StringUtil.getIfBlank(mapping.getTargetDatabase(), ""));
                    group.setSourceSchema(StringUtil.getIfBlank(mapping.getSourceSchema(), ""));
                    group.setTargetSchema(StringUtil.getIfBlank(mapping.getTargetSchema(), ""));
                    group.setSourceTable(sourceTable);
                    Table targetTable = new Table();
                    targetTable.setName(name);
                    targetTable.setType(TableTypeEnum.TABLE.getCode());
                    group.setTargetTable(targetTable);
                    group.setCreateTime(now);
                    group.setUpdateTime(now);
                    String id = tableGroupProfile.addTableGroup(group);
                    if (StringUtil.isNotBlank(id)) {
                        group.setId(id);
                    }
                    created.add(group);
                    knownTablesByKey.add(clusterKey + "::" + name.toLowerCase());
                    logger.info("整库迁移任务 [{}] 发现源端新表: {} -> {} (index={})", task.getId(), name, name, group.getIndex());
                }
            } catch (Exception e) {
                logger.error("整库迁移任务 [{}] 扫描簇 [{}] 新表失败（跳过该簇）: {}",
                        task.getId(), clusterKey, e.getMessage(), e);
            }
        }
        return created;
    }

    private Map<String, Table> loadSourceTableMetaMap(String taskId, DatabaseMapping mapping, List<String> tableNames) {
        DefaultConnectorServiceContext ctx = ConnectorServiceContextUtil.buildConnectorServiceContext(
                taskId, mapping.getSourceConnectorId(), mapping.getSourceDatabase(), mapping.getSourceSchema(),
                mapping.getTargetConnectorId(), mapping.getTargetDatabase(), mapping.getTargetSchema(), true);
        tableNames.stream().distinct().forEach(ctx::addTablePattern);
        List<MetaInfo> metaInfos = parserComponent.getMetaInfo(ctx);
        Map<String, Table> tableMap = new HashMap<>(metaInfos.size());
        for (MetaInfo metaInfo : metaInfos) {
            if (metaInfo == null || StringUtil.isBlank(metaInfo.getTable())) {
                continue;
            }
            Table table = new Table();
            table.setName(metaInfo.getTable());
            table.setType(metaInfo.getTableType());
            table.setColumn(metaInfo.getColumn());
            tableMap.put(metaInfo.getTable(), table);
        }
        return tableMap;
    }

    private Mapping buildStubMapping(DatabaseSyncTask task, TableGroup group) {
        Mapping mapping = new Mapping();
        mapping.setId(task.getId());
        mapping.setSourceConnectorId(group.getSourceConnectorId());
        mapping.setSourceDatabase(group.getSourceDatabase());
        mapping.setSourceSchema(group.getSourceSchema());
        mapping.setTargetConnectorId(group.getTargetConnectorId());
        mapping.setTargetDatabase(group.getTargetDatabase());
        mapping.setTargetSchema(group.getTargetSchema());
        mapping.setReadNum(task.getReadNum() > 0 ? task.getReadNum() : 10000);
        mapping.setBatchNum(task.getBatchNum() > 0 ? task.getBatchNum() : 1000);
        mapping.setForceUpdate(task.isOverwriteData());
        return mapping;
    }

    private void connectInstances(Mapping mapping) {
        Connector source = profileComponent.getConnector(mapping.getSourceConnectorId());
        if (source == null || source.getConfig() == null) {
            throw new IllegalStateException("源连接器不存在或缺少配置");
        }
        Connector target = profileComponent.getConnector(mapping.getTargetConnectorId());
        if (target == null || target.getConfig() == null) {
            throw new IllegalStateException("目标连接器不存在或缺少配置");
        }
        connectorFactory.connect(ConnectorInstanceUtil.buildConnectorInstanceId(mapping.getId(), mapping.getSourceConnectorId(), "S"),
                source.getConfig(), mapping.getSourceDatabase(), mapping.getSourceSchema());
        connectorFactory.connect(ConnectorInstanceUtil.buildConnectorInstanceId(mapping.getId(), mapping.getTargetConnectorId(), "T"),
                target.getConfig(), mapping.getTargetDatabase(), mapping.getTargetSchema());
    }

    /**
     * 集簇级自动建库（结构迁移）：目标库缺失时按目标连接器方言 CREATE DATABASE。
     * 必须在任何目标端元数据读取之前执行，避免连接器实例回退默认库造成读写错位。
     */
    private void ensureTargetDatabase(Mapping stub) {
        String targetDatabaseName = stub.getTargetDatabase();
        String targetSchemaName = stub.getTargetSchema();
        if (StringUtil.isBlank(targetDatabaseName) && StringUtil.isBlank(targetSchemaName)) {
            return;
        }
        ConnectorInstance targetInstance = connectorFactory.connect(
                ConnectorInstanceUtil.buildConnectorInstanceId(stub.getId(), stub.getTargetConnectorId(), "T"));
        Assert.notNull(targetInstance, "目标连接器实例不存在");
        Object targetService = connectorFactory.getConnectorService(targetInstance.getConfig());
        if (!(targetService instanceof Database)) {
            throw new IllegalStateException(String.format("目标连接器 [%s] 不支持结构迁移（自动建库），请先手动建库",
                    stub.getTargetConnectorId()));
        }
        Database targetDatabaseConnector = (Database) targetService;
        boolean exists = false;
        try {
            exists = targetDatabaseConnector.databaseExists((DatabaseConnectorInstance) targetInstance,
                    targetDatabaseName, targetSchemaName);
        } catch (Exception e) {
            logger.debug("目标库存在性检查失败（按不存在处理）: {}", e.getMessage());
        }
        if (!exists) {
            String createDatabase = targetDatabaseConnector.buildCreateDatabaseSql(targetDatabaseName, targetSchemaName);
            executeDDL(targetInstance, createDatabase);
            logger.info("自动创建目标库: {}",
                    StringUtil.isNotBlank(targetDatabaseName) ? targetDatabaseName : targetSchemaName);
        }
    }

    /**
     * 按字段名（忽略大小写）匹配构建字段映射，目标缺列时回退构建同名字段（保持写入可执行，实际写入由目标端处理）。
     */
    private List<FieldMapping> buildFieldMapping(TableGroup group, Table targetMeta) {
        List<Field> sourceColumns = group.getSourceTable() != null ? group.getSourceTable().getColumn() : null;
        if (CollectionUtils.isEmpty(sourceColumns)) {
            throw new IllegalStateException(String.format("源表 [%s] 缺少字段元数据，请重新保存库映射", safeName(group, true)));
        }
        Map<String, Field> targetColumnMap = new HashMap<>();
        if (targetMeta.getColumn() != null) {
            for (Field field : targetMeta.getColumn()) {
                if (field == null || StringUtil.isBlank(field.getName())) {
                    continue;
                }
                targetColumnMap.put(field.getName().toLowerCase(), field);
            }
        }
        List<FieldMapping> fieldMapping = new ArrayList<>(sourceColumns.size());
        for (Field source : sourceColumns) {
            if (source == null || StringUtil.isBlank(source.getName())) {
                continue;
            }
            Field target = targetColumnMap.get(source.getName().toLowerCase());
            if (target == null) {
                target = new Field(source.getName(), source.getTypeName(), source.getType(), source.isPk(),
                        source.getColumnSize(), source.getRatio());
            }
            fieldMapping.add(new FieldMapping(source, target));
        }
        return fieldMapping;
    }

    /**
     * 按 目标连接|库|构架 聚组批量读回目标表元数据（一次性回读，供同簇全部表复用）。
     */
    private Map<String, Table> loadTargetTableMetas(String taskId, List<TableGroup> tableGroups) {
        Map<String, LinkedHashSet<String>> targetNamesByKey = new HashMap<>();
        Map<String, TableGroup> keySample = new HashMap<>();
        for (TableGroup group : tableGroups) {
            String key = buildTargetKey(group);
            if (StringUtil.isBlank(key)) {
                continue;
            }
            targetNamesByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(group.getTargetTable().getName());
            keySample.putIfAbsent(key, group);
        }
        Map<String, Table> result = new HashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : targetNamesByKey.entrySet()) {
            TableGroup sample = keySample.get(entry.getKey());
            DefaultConnectorServiceContext context = ConnectorServiceContextUtil.buildConnectorServiceContext(
                    taskId, sample.getSourceConnectorId(), sample.getSourceDatabase(), sample.getSourceSchema(),
                    sample.getTargetConnectorId(), sample.getTargetDatabase(), sample.getTargetSchema(), false);
            entry.getValue().forEach(context::addTablePattern);
            List<MetaInfo> metaInfos = parserComponent.getMetaInfo(context);
            for (MetaInfo metaInfo : metaInfos) {
                if (metaInfo == null || StringUtil.isBlank(metaInfo.getTable())) {
                    continue;
                }
                Table table = new Table();
                table.setName(metaInfo.getTable());
                table.setType(metaInfo.getTableType());
                table.setColumn(metaInfo.getColumn());
                result.put(entry.getKey() + "::" + metaInfo.getTable().toLowerCase(), table);
            }
        }
        return result;
    }

    private String buildTargetKey(TableGroup group) {
        return StringUtil.join(Arrays.asList(group.getTargetConnectorId(), group.getTargetDatabase(), group.getTargetSchema()), "|");
    }

    private String buildTargetTableKey(TableGroup group) {
        Table targetTable = group.getTargetTable();
        String tableName = targetTable != null ? targetTable.getName() : "";
        return buildTargetKey(group) + "::" + tableName.toLowerCase();
    }

    private List<TableGroup> loadTableGroups(String taskId) {
        List<TableGroup> groups = new ArrayList<>();
        tableGroupProfile.pageScanTableGroups(taskId, 100, groups::addAll);
        groups.sort(Comparator.comparingInt(TableGroup::getIndex));
        return groups;
    }

    private Map<String, Integer> buildMappingIndex(DatabaseSyncTask task) {
        Map<String, Integer> indexByKey = new HashMap<>();
        if (task.getDatabaseMappings() != null) {
            for (DatabaseMapping mapping : DatabaseSyncMappingUtil.sortByIndex(task.getDatabaseMappings())) {
                if (mapping == null) {
                    continue;
                }
                indexByKey.put(StringUtil.join(Arrays.asList(
                        mapping.getSourceConnectorId(), mapping.getSourceDatabase(), mapping.getSourceSchema(),
                        mapping.getTargetConnectorId(), mapping.getTargetDatabase(), mapping.getTargetSchema()), "|"), mapping.getIndex());
            }
        }
        return indexByKey;
    }

    private Meta ensureTaskMeta(String taskId, Task rootTask) {
        Meta meta = metaProfile.getMetaByTaskId(taskId, TaskLevelEnum.TASK);
        if (meta == null) {
            meta = new Meta();
            meta.setId(taskId);
            meta.setTaskId(taskId);
            meta.setIsTaskDetail(TaskLevelEnum.TASK.getCode());
            meta.setCreateTime(System.currentTimeMillis());
            metaProfile.addMeta(meta);
        }
        meta.setBeginTime(rootTask.getBeginTime() > 0 ? rootTask.getBeginTime() : System.currentTimeMillis());
        rootTask.setBeginTime(meta.getBeginTime());
        meta.setState(CommonTaskStatusEnum.RUNNING.getCode());
        return meta;
    }

    private void finishTaskMeta(Meta taskMeta, boolean success) {
        try {
            taskMeta.setEndTime(System.currentTimeMillis());
            taskMeta.setState(success ? CommonTaskStatusEnum.DONE.getCode() : CommonTaskStatusEnum.READY.getCode());
            metaProfile.updateMeta(taskMeta);
        } catch (Exception e) {
            logger.error("整库迁移任务收尾状态更新失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 写表级明细快照（成功/失败 + 错误信息），并把整库状态摘要推进到任务级 Meta。
     */
    private void markGroupResult(String taskId, TableGroup group, Map<String, Integer> mappingIndexByKey, boolean success, String error) {
        try {
            Meta detailMeta = metaProfile.getMetaByTaskId(group.getId(), TaskLevelEnum.TASK_DETAIL);
            if (detailMeta == null) {
                detailMeta = new Meta();
                detailMeta.setTaskId(group.getId());
                detailMeta.setIsTaskDetail(TaskLevelEnum.TASK_DETAIL.getCode());
                detailMeta.setCreateTime(System.currentTimeMillis());
                metaProfile.addMeta(detailMeta);
            }
            CommonTaskSnapshot snapshot = new CommonTaskSnapshot();
            snapshot.setStep(DatabaseMigrationDetailTypeEnum.ROW_DATA.getCode());
            snapshot.setStatus(success ? CommonTaskStatusEnum.DONE.getCode() : CommonTaskStatusEnum.READY.getCode());
            TaskSnapshotUtil.writeTableSnapshot(detailMeta.getSnapshot(), snapshot, mappingIndexByKey.get(buildSourceTargetKey(group)));
            detailMeta.setUpdateTime(System.currentTimeMillis());
            metaProfile.updateMeta(detailMeta);
            Integer mappingIndex = mappingIndexByKey.get(buildSourceTargetKey(group));
            if (mappingIndex != null && success) {
                Meta taskMeta = metaProfile.getMetaByTaskId(taskId, TaskLevelEnum.TASK);
                if (taskMeta != null) {
                    taskMeta.setSnapshot(TaskSnapshotUtil.putMappingStatus(taskMeta.getSnapshot(), mappingIndex, CommonTaskStatusEnum.DONE.getCode()));
                    taskMeta.setUpdateTime(System.currentTimeMillis());
                    metaProfile.updateMeta(taskMeta);
                }
            }
            if (!success && StringUtil.isNotBlank(error)) {
                logger.error("整库迁移表 [{}] >> [{}] 失败: {}", safeName(group, true), safeName(group, false), error);
            }
        } catch (Exception e) {
            logger.error("整库迁移明细状态更新失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 同簇 key：源连接|源库|源构架|目标连接|目标库|目标构架（与库映射 index 对齐）。
     */
    private String buildSourceTargetKey(TableGroup group) {
        return StringUtil.join(Arrays.asList(
                group.getSourceConnectorId(), group.getSourceDatabase(), group.getSourceSchema(),
                group.getTargetConnectorId(), group.getTargetDatabase(), group.getTargetSchema()), "|");
    }

    private String safeName(TableGroup group, boolean source) {
        Table table = source ? group.getSourceTable() : group.getTargetTable();
        return table != null ? table.getName() : "";
    }
}
