/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.parser.flush.impl;

import org.dbdatasync.common.QueueOverflowException;
import org.dbdatasync.common.config.GeneralBufferConfig;
import org.dbdatasync.common.metric.TimeRegistry;
import org.dbdatasync.common.model.Result;
import org.dbdatasync.common.rsa.RsaManager;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.base.ConnectorFactory;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.ParserComponent;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.TableGroupContext;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.ddl.DDLParser;
import org.dbdatasync.parser.event.RefreshOffsetEvent;
import org.dbdatasync.parser.flush.AbstractBufferActuator;
import org.dbdatasync.parser.model.Connector;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.model.TableGroupPicker;
import org.dbdatasync.parser.model.WriterRequest;
import org.dbdatasync.parser.model.WriterResponse;
import org.dbdatasync.parser.strategy.FlushStrategy;
import org.dbdatasync.parser.util.ConnectorInstanceUtil;
import org.dbdatasync.parser.util.ConnectorServiceContextUtil;
import org.dbdatasync.parser.util.ConvertUtil;
import org.dbdatasync.plugin.PluginFactory;
import org.dbdatasync.plugin.enums.ProcessEnum;
import org.dbdatasync.plugin.impl.IncrementPluginContext;
import org.dbdatasync.sdk.config.DDLConfig;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.connector.database.Database;
import org.dbdatasync.sdk.connector.database.DatabaseConnectorInstance;
import org.dbdatasync.sdk.model.ValidateSyncTask;
import org.dbdatasync.sdk.connector.DefaultConnectorServiceContext;
import org.dbdatasync.sdk.enums.ChangedEventTypeEnum;
import org.dbdatasync.sdk.model.ChangedOffset;
import org.dbdatasync.sdk.model.ConnectorConfig;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.MetaInfo;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.spi.ConnectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 通用执行器（单线程消费，多线程批量写，按序执行）
 *
 * @Version 1.0.0
 * @Author AE86
 * @Date 2022-03-27 16:50
 */
@Component
public class GeneralBufferActuator extends AbstractBufferActuator<WriterRequest, WriterResponse> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private GeneralBufferConfig generalBufferConfig;

    @Resource
    private Executor generalExecutor;

    @Resource
    private ConnectorFactory connectorFactory;

    @Resource
    private ParserComponent parserComponent;

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private PluginFactory pluginFactory;

    @Resource
    private FlushStrategy flushStrategy;

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private DDLParser ddlParser;

    @Resource
    private TableGroupContext tableGroupContext;

    @Resource
    private RsaManager rsaManager;

    @PostConstruct
    public void init() {
        setConfig(generalBufferConfig);
        buildConfig();
    }

    @Override
    protected String getPartitionKey(WriterRequest request) {
        return request.getTableName();
    }

    @Override
    protected void partition(WriterRequest request, WriterResponse response) {
        if (!CollectionUtils.isEmpty(request.getRow())) {
            response.addData(request.getRow());
        }
        if (request.getChangedOffset() != null) {
            response.setChangedOffset(request.getChangedOffset());
        }
        if (!response.isMerged()) {
            response.setTraceId(request.getTraceId());
            response.setTableName(request.getTableName());
            response.setEvent(request.getEvent());
            response.setTypeEnum(request.getTypeEnum());
            response.setSql(request.getSql());
            response.setMerged(true);
        } else if (profileComponent.getSystemConfig().isEnablePrintTraceInfo() && StringUtil.isNotBlank(request.getTraceId())) {
            logger.info("traceId:{} merge into traceId:{}", request.getTraceId(), response.getTraceId());
        }
    }

    @Override
    protected boolean skipPartition(WriterRequest nextRequest, WriterResponse response) {
        // 并发场景，同一条数据可能连续触发Insert > Delete > Insert，批处理任务中出现不同事件时，跳过分区处理
        // 跳过表结构修改事件（保证表结构修改原子性）
        return !StringUtil.equals(nextRequest.getEvent(), response.getEvent()) || ChangedEventTypeEnum.isDDL(response.getTypeEnum());
    }

    @Override
    public void pull(WriterResponse response) {
        // Wave24：HA fencing 栅栏。旧节点租约被接管后 token 失效，禁止继续写入造成双写
        String fenceMetaId = response.getChangedOffset() == null ? null
                : response.getChangedOffset().getMetaId();
        if (!org.dbdatasync.sdk.spi.FencingTokens.isValid(fenceMetaId)) {
            logger.error("fencing token 已失效（租约被接管/过期），拒绝陈旧节点写入: metaId={}", fenceMetaId);
            org.dbdatasync.sdk.observer.ReplicationEventObservers.notifyDeadLetter(fenceMetaId,
                    "PERMANENT", "HA fencing token 失效，本节点写入被拒绝");
            response.getDataList().clear();
            return;
        }
        Meta meta = metaProfile.getMeta(response.getChangedOffset().getMetaId());
        if (meta == null) {
            return;
        }
        // 打印trace信息
        printTraceInfo(response);
        final Mapping mapping = profileComponent.getMapping(meta.getTaskId());
        List<TableGroupPicker> pickers = tableGroupContext.getTableGroupPickers(meta.getId(), response.getTableName());

        switch (response.getTypeEnum()) {
            case DDL:
                tableGroupContext.update(mapping, pickers.stream().map(picker-> {
                    TableGroup tableGroup = tableGroupProfile.getTableGroup(picker.getTableGroup().getId());
                    parseDDl(response, mapping, tableGroup);
                    return tableGroup;
                }).collect(Collectors.toList()));
                break;
            case SCAN:
                pickers.forEach(picker->distributeTableGroup(response, mapping, picker, picker.getSourceFields(), false));
                break;
            case ROW:
                // Wave24：登记本轮待提交位点，由目标库事务内的位点表写入消费（强一致收敛）
                if (org.dbdatasync.sdk.checkpoint.TargetTxCheckpoint.isEnabled()
                        && response.getChangedOffset() != null) {
                    ChangedOffset off = response.getChangedOffset();
                    org.dbdatasync.sdk.checkpoint.TargetTxCheckpoint.begin(
                            new org.dbdatasync.sdk.checkpoint.CheckpointRecord(
                                    off.getMetaId(), off.getNextFileName(), String.valueOf(off.getPosition()),
                                    off.getSeq(), off.getEventTime(), off.getEventTimeSource(),
                                    off.getIdempotencyKey()));
                }
                try {
                    pickers.forEach(picker->distributeTableGroup(response, mapping, picker, picker.getTableGroup().getSourceTable().getColumn(), true));
                    if (shouldPublishOffsetRefresh()) {
                        // 文件 checkpoint 仍双写，作为恢复兼容与运维备份；权威位点为目标库表
                        publishOffsetRefresh(response.getChangedOffset());
                    }
                } finally {
                    org.dbdatasync.sdk.checkpoint.TargetTxCheckpoint.end();
                }
                break;
            default:
                break;
        }
        // 及时清空列表引用，便于 GC 回收，减轻 parser 侧 LinkedList 保留内存
        response.getDataList().clear();
    }

    protected boolean shouldPublishOffsetRefresh() {
        return true;
    }

    /**
     * 发布增量位点刷新事件。
     *
     * @param changedOffset 位点
     */
    protected void publishOffsetRefresh(ChangedOffset changedOffset) {
        if (changedOffset != null) {
            // Wave23：数据写入成功后，先原子提交「位点 + 幂等键」，再刷新 listener 位点
            org.dbdatasync.sdk.checkpoint.TransactionalCheckpoint cp =
                    org.dbdatasync.sdk.checkpoint.Checkpoints.coordinator();
            if (cp != null) {
                try {
                    cp.commit(new org.dbdatasync.sdk.checkpoint.CheckpointRecord(
                            changedOffset.getMetaId(), changedOffset.getNextFileName(),
                            String.valueOf(changedOffset.getPosition()), changedOffset.getSeq(),
                            changedOffset.getEventTime(), changedOffset.getEventTimeSource(),
                            changedOffset.getIdempotencyKey()));
                } catch (Exception e) {
                    logger.warn("commit transactional checkpoint failed: {}", e.getMessage());
                }
            }
            applicationContext.publishEvent(new RefreshOffsetEvent(applicationContext, changedOffset));
        }
    }

    @Override
    protected void offerFailed(BlockingQueue<WriterRequest> queue, WriterRequest request) {
        throw new QueueOverflowException("缓存队列已满");
    }

    @Override
    protected void meter(TimeRegistry timeRegistry, long count) {
        // 统计执行器同步效率TPS
        timeRegistry.meter(TimeRegistry.GENERAL_BUFFER_ACTUATOR_TPS).add(count);
    }

    @Override
    public Executor getExecutor() {
        return generalExecutor;
    }

    private void distributeTableGroup(WriterResponse response, Mapping mapping, TableGroupPicker tableGroupPicker, List<Field> sourceFields, boolean enableFilter) {
        // Wave24：DDL-DML 顺序一致性守卫。表级 DDL 未确认/未应用时，同表后续 DML
        // 复制到独立快照后挂起，确认应用后按原顺序重放，避免按旧 schema 写入。
        if (enableDdlDmlOrderGuard() && org.dbdatasync.parser.ddl.DdlSyncOrchestrator.current()
                .isTableBlocked(response.getTableName())) {
            WriterResponse snapshot = new WriterResponse();
            snapshot.setTypeEnum(response.getTypeEnum());
            snapshot.setChangedOffset(response.getChangedOffset());
            snapshot.setTableName(response.getTableName());
            snapshot.setEvent(response.getEvent());
            snapshot.setSql(response.getSql());
            snapshot.setTraceId(response.getTraceId());
            List<List<Object>> rowsCopy = new ArrayList<>(response.getDataList());
            for (List<Object> row : rowsCopy) {
                snapshot.addData(row);
            }
            boolean accepted = org.dbdatasync.parser.ddl.DdlSyncOrchestrator.current().suspendDml(
                    response.getTableName(),
                    () -> distributeTableGroup(snapshot, mapping, tableGroupPicker, sourceFields, enableFilter));
            if (accepted) {
                logger.warn("表 {} 存在未确认 DDL，挂起 {} 条 DML 等待确认后按序重放",
                        response.getTableName(), rowsCopy.size());
            } else {
                logger.error("表 {} 挂起队列已满，本批 {} 条 DML 未能写入（需尽快确认 DDL）",
                        response.getTableName(), rowsCopy.size());
                org.dbdatasync.sdk.observer.ReplicationEventObservers.notifyDeadLetter(
                        mapping.getMetaId(), "PERMANENT",
                        "DDL 未确认且挂起队列已满，表 " + response.getTableName() + " 的 DML 被拒绝");
            }
            return;
        }
        // 1、映射字段
        ConnectorConfig sourceConfig = getConnectorConfig(mapping.getSourceConnectorId());
        ConnectorService sourceConnector = connectorFactory.getConnectorService(sourceConfig.getConnectorType());
        List<Map> sourceDataList = new ArrayList<>();
        List<Map> targetDataList = tableGroupPicker.getPicker().setSourceResolver(sourceConnector.getSchemaResolver())
                .pickTargetData(sourceFields, enableFilter, response.getDataList(), sourceDataList);
        if (CollectionUtils.isEmpty(targetDataList)) {
            return;
        }

        // 2、参数转换
        TableGroup tableGroup = tableGroupPicker.getTableGroup();
        ConvertUtil.convert(tableGroup.getConvert(), targetDataList);

        // 3、插件转换
        final IncrementPluginContext context = new IncrementPluginContext();
        String sourceInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mapping.getId(), mapping.getSourceConnectorId(), ConnectorInstanceUtil.SOURCE_SUFFIX);
        String targetInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mapping.getId(), mapping.getTargetConnectorId(), ConnectorInstanceUtil.TARGET_SUFFIX);
        context.setSourceConnectorInstance(connectorFactory.connect(sourceInstanceId));
        context.setTargetConnectorInstance(connectorFactory.connect(targetInstanceId));
        context.setSourceTable(tableGroup.getSourceTable());
        context.setTargetTable(tableGroup.getTargetTable());
        context.setTraceId(response.getTraceId());
        context.setEvent(response.getEvent());
        context.setTargetFields(tableGroupPicker.getTargetFields());
        context.setCommand(tableGroup.getCommand());
        context.setBatchSize(getBufferWriterCount());
        context.setSourceList(sourceDataList);
        context.setTargetList(targetDataList);
        context.setPlugin(tableGroup.getPlugin());
        context.setPluginExtInfo(tableGroup.getPluginExtInfo());
        context.setForceUpdate(mapping.isForceUpdate());
        context.setEnablePrintTraceInfo(StringUtil.isNotBlank(response.getTraceId()));
        setRsaConfig(context);
        pluginFactory.process(context, ProcessEnum.CONVERT);

        // 4、批量执行同步
        // Wave24：设置任务级上下文（线程池子任务由 ParserComponentImpl 显式透传），
        // 并在同一观测点采集大事务批写耗时/行数分布。
        Meta taskMeta = metaProfile.getMeta(mapping.getMetaId());
        String taskId = taskMeta != null ? taskMeta.getTaskId() : mapping.getId();
        org.dbdatasync.sdk.context.TaskContextHolder.set(taskId);
        long batchStartNanos = System.nanoTime();
        Result result;
        try {
            result = parserComponent.writeBatch(context, getExecutor());
        } finally {
            org.dbdatasync.sdk.context.TaskContextHolder.clear();
            org.dbdatasync.sdk.profile.BatchWriteProfiler.instance().record(targetDataList.size(),
                    (System.nanoTime() - batchStartNanos) / 1_000_000L);
        }

        // 5、持久化同步结果
        result.setTableGroupId(tableGroup.getId());
        result.setTargetTableGroupName(context.getTargetTable().getName());
        result.setMetaId(mapping.getMetaId());
        result.setEvent(response.getEvent());
        if (response.getChangedOffset() != null) {
            result.setReplayId(response.getChangedOffset().getReplayId());
        }
        ConnectorConfig targetConfig = getConnectorConfig(mapping.getTargetConnectorId());
        ConnectorService targetConnectorService = connectorFactory.getConnectorService(targetConfig);
        flushStrategy.flushIncrementData(result, targetConnectorService.getSchemaResolver(), tableGroupPicker.getTargetFieldMap());

        // 6、执行后置处理
        pluginFactory.process(context, ProcessEnum.AFTER);
    }

    /** Wave24：DDL-DML 顺序守卫开关（默认开启，配置不可读时保守开启） */
    private boolean enableDdlDmlOrderGuard() {
        try {
            org.dbdatasync.parser.model.SystemConfig sc = profileComponent.getSystemConfig();
            return sc == null || sc.isEnableDdlDmlOrderGuard();
        } catch (Exception e) {
            return true;
        }
    }

    /** Wave24：schema history 落盘目录 */
    private String schemaHistoryDir() {
        try {
            org.dbdatasync.parser.model.SystemConfig sc = profileComponent.getSystemConfig();
            return sc == null || StringUtil.isBlank(sc.getSchemaHistoryDir())
                    ? "data/schema-history" : sc.getSchemaHistoryDir();
        } catch (Exception e) {
            return "data/schema-history";
        }
    }

    private void setRsaConfig(IncrementPluginContext context) {
        if (profileComponent.getSystemConfig().isEnableOpenAPI()) {
            context.setRsaManager(rsaManager);
            context.setRsaConfig(profileComponent.getSystemConfig().getRsaConfig());
        }
    }

    /**
     * 解析DDL
     */
    private void parseDDl(WriterResponse response, Mapping mapping, TableGroup tableGroup) {
        try {
            // DDL 风险分级策略: off=完全关闭; add_only=仅新增字段自动同步(改类型/删列/改名需人工确认);
            // all=全部 ALTER 自动同步(默认, 保持历史行为)
            String policy = "all";
            try {
                org.dbdatasync.parser.model.SystemConfig sc = profileComponent.getSystemConfig();
                policy = sc == null || StringUtil.isBlank(sc.getDdlSyncPolicy()) ? "all" : sc.getDdlSyncPolicy().trim().toLowerCase();
            } catch (Exception ignored) {
            }
            if ("off".equals(policy)) {
                logger.info("DDL 同步策略为 off, 跳过 DDL: {}", response.getSql());
                return;
            }
            ConnectorConfig sConnConfig = getConnectorConfig(mapping.getSourceConnectorId());
            ConnectorConfig tConnConfig = getConnectorConfig(mapping.getTargetConnectorId());
            String sConnType = sConnConfig.getConnectorType();
            String tConnType = tConnConfig.getConnectorType();
            String instanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mapping.getId(), mapping.getTargetConnectorId(), ConnectorInstanceUtil.TARGET_SUFFIX);
            ConnectorInstance tConnectorInstance = connectorFactory.connect(instanceId);
            ConnectorService connectorService = connectorFactory.getConnectorService(tConnType);
            DDLConfig targetDDLConfig = ddlParser.parse(tConnectorInstance, connectorService, tableGroup, response.getSql());
            // Wave24：表级 DDL 入 schema history，并按开关自动同步；高风险类型仍须人工确认
            org.dbdatasync.sdk.enums.DDLOperationEnum tableLevel =
                    org.dbdatasync.parser.ddl.DDLStatementClassifier.classifyTableLevel(response.getSql());
            if (tableLevel != null) {
                org.dbdatasync.parser.ddl.SchemaHistory.current().configure(schemaHistoryDir());
                boolean autoSync = profileComponent.getSystemConfig() != null
                        && profileComponent.getSystemConfig().isEnableDdlAutoSync();
                final ConnectorInstance ddlInstance = tConnectorInstance;
                org.dbdatasync.parser.ddl.DdlSyncOrchestrator.DdlExecutor executor = (targetSql, operation) -> {
                    DDLConfig cfg = new DDLConfig();
                    cfg.setSql(targetSql);
                    Result ddlResult = connectorFactory.writerDDL(ddlInstance, cfg);
                    if (ddlResult.getError().length() > 0 || CollectionUtils.isEmpty(ddlResult.getSuccessData())) {
                        throw new IllegalStateException("目标端 DDL 执行失败: " + ddlResult.getError());
                    }
                    return "writerDDL applied";
                };
                long eventTime = response.getChangedOffset() == null ? 0L
                        : response.getChangedOffset().getEventTime();
                org.dbdatasync.parser.ddl.SchemaHistory.Entry entry =
                        org.dbdatasync.parser.ddl.DdlSyncOrchestrator.current().record(
                                mapping.getMetaId(), response.getTableName(), tableLevel,
                                response.getSql(), null, eventTime, autoSync,
                                StringUtil.equals(sConnType, tConnType), executor);
                logger.warn("表级 DDL 已登记 schema history: id={}, status={}, operation={}, sql={}",
                        entry.getId(), entry.getStatus(), tableLevel, response.getSql());
            } else {
                // Wave32：列级 ALTER（ADD/MODIFY COLUMN）此前完全不入账，
                // 导致结构变更页恒 0；这里按「已处理」登记，不阻塞该表 DML。
                org.dbdatasync.parser.ddl.SchemaHistory.current().configure(schemaHistoryDir());
                long columnEventTime = response.getChangedOffset() == null ? 0L
                        : response.getChangedOffset().getEventTime();
                org.dbdatasync.parser.ddl.SchemaHistory.current().recordEvent(
                        mapping.getMetaId(), response.getTableName(), "ALTER_COLUMN",
                        response.getSql(),
                        targetDDLConfig == null ? "" : targetDDLConfig.getSql(),
                        columnEventTime,
                        org.dbdatasync.parser.ddl.SchemaHistory.STATUS_APPLIED,
                        "列级 DDL 已登记（目标端按 ddlSyncPolicy 处理）");
            }
            if ("add_only".equals(policy) && targetDDLConfig.getDdlOperationEnum() != null
                    && targetDDLConfig.getDdlOperationEnum() != org.dbdatasync.sdk.enums.DDLOperationEnum.ALTER_ADD) {
                logger.warn("DDL 风险策略为 add_only, 拦截非新增字段变更({}), 请人工确认后在目标端执行: {}",
                        targetDDLConfig.getDdlOperationEnum(), response.getSql());
                return;
            }
            // 1.生成目标表执行SQL: 同源直接回放; 跨源按目标方言重建(列类型经 formatPhysicalType 翻译)
            if (!StringUtil.equals(sConnType, tConnType)) {
                // 跨源: 源端 DDL 已生效, 先刷新源表列定义, 再用源列定义翻译成目标方言 DDL
                updateTableColumn(mapping, ConnectorInstanceUtil.SOURCE_SUFFIX, tableGroup.getSourceTable());
                String crossTypeSql = buildCrossTypeDdl(tConnectorInstance, connectorService, tableGroup, targetDDLConfig);
                targetDDLConfig.setSql(crossTypeSql);
            }
            if (mapping.getListener().isEnableDDL() && StringUtil.isNotBlank(targetDDLConfig.getSql())) {
                Result result = connectorFactory.writerDDL(tConnectorInstance, targetDDLConfig);
                // 2.持久化增量事件数据
                result.setTableGroupId(tableGroup.getId());
                result.setTargetTableGroupName(tableGroup.getTargetTable().getName());
                result.setMetaId(mapping.getMetaId());
                result.setEvent(response.getEvent());
                flushStrategy.flushIncrementData(result, connectorService.getSchemaResolver(), null);
            }

            // 3.更新表属性字段
            updateTableColumn(mapping, ConnectorInstanceUtil.SOURCE_SUFFIX, tableGroup.getSourceTable());
            updateTableColumn(mapping, ConnectorInstanceUtil.TARGET_SUFFIX, tableGroup.getTargetTable());

            // 4.更新表字段映射关系
            ddlParser.refreshFiledMappings(tableGroup, targetDDLConfig);

            // 5.更新执行命令
            tableGroup.setCommand(parserComponent.getCommand(mapping, tableGroup));

            // 6.持久化存储 & 更新缓存配置
            tableGroupProfile.editTableGroup(tableGroup);

            // 7.发布更新事件
            if (shouldPublishOffsetRefresh()) {
                publishOffsetRefresh(response.getChangedOffset());
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            // Wave32：DDL 执行失败也要如实入账，不能只打日志让账本一片空白。
            try {
                org.dbdatasync.parser.ddl.SchemaHistory.current().configure(schemaHistoryDir());
                org.dbdatasync.parser.ddl.SchemaHistory.current().recordEvent(
                        mapping == null ? "" : mapping.getMetaId(),
                        response == null ? "" : response.getTableName(),
                        "DDL_ERROR",
                        response == null ? "" : response.getSql(),
                        "", 0L,
                        org.dbdatasync.parser.ddl.SchemaHistory.STATUS_FAILED,
                        "目标端 DDL 执行失败: " + e.getMessage());
            } catch (Exception ignored) {
                logger.warn("persist failed schema history entry error: {}", ignored.getMessage());
            }
        }
    }

    /**
     * 跨源 DDL 重建: 源端 ALTER 已生效, 依据源表最新列定义 + 目标连接器方言能力,
     * 生成目标端等价 DDL(列类型经目标方言 formatPhysicalType 翻译)。
     * 仅支持目标为数据库类连接器; 不支持的操作(如跨源重命名)返回空串跳过执行。
     */
    private String buildCrossTypeDdl(ConnectorInstance tConnectorInstance, ConnectorService connectorService,
                                     TableGroup tableGroup, DDLConfig ddlConfig) {
        if (ddlConfig == null || ddlConfig.getDdlOperationEnum() == null) {
            return StringUtil.EMPTY;
        }
        if (!(connectorService instanceof Database)) {
            return StringUtil.EMPTY;
        }
        Database database = (Database) connectorService;
        String tableName = tableGroup.getTargetTable().getName();
        // 限定名: PG/金仓系按 schema 前缀, MySQL 系按 catalog(库) 前缀
        String schema = ((DatabaseConnectorInstance) tConnectorInstance).getSchema();
        String catalog = ((DatabaseConnectorInstance) tConnectorInstance).getCatalog();
        String namePart = StringUtil.isNotBlank(schema) ? schema
                : (StringUtil.isNotBlank(catalog) ? catalog : StringUtil.EMPTY);
        String qualifiedName = StringUtil.isNotBlank(namePart)
                ? namePart + "." + database.buildWithQuotation(tableName)
                : database.buildWithQuotation(tableName);

        switch (ddlConfig.getDdlOperationEnum()) {
            case ALTER_ADD: {
                List<Field> addFields = new ArrayList<>();
                for (Field column : tableGroup.getSourceTable().getColumn()) {
                    if (column != null && ddlConfig.getAddedFieldNames().contains(column.getName())) {
                        addFields.add(column);
                    }
                }
                return database.buildAddColumnsSql((DatabaseConnectorInstance) tConnectorInstance, tableName,
                        qualifiedName, addFields);
            }
            case ALTER_MODIFY: {
                List<Field> modifyFields = new ArrayList<>();
                List<String> modifyNames = new ArrayList<>();
                for (Field column : tableGroup.getSourceTable().getColumn()) {
                    if (column != null && ddlConfig.getModifiedFieldNames().contains(column.getName())) {
                        modifyFields.add(column);
                        modifyNames.add(column.getName());
                    }
                }
                if (modifyFields.isEmpty()) {
                    return StringUtil.EMPTY;
                }
                ValidateSyncTask pseudoTask = new ValidateSyncTask();
                pseudoTask.setTargetDatabase(catalog);
                return database.buildModifyColumnsSql((DatabaseConnectorInstance) tConnectorInstance, pseudoTask,
                        tableName, modifyFields, modifyNames);
            }
            case ALTER_DROP:
                return database.buildDropColumnsSql((DatabaseConnectorInstance) tConnectorInstance, tableName,
                        qualifiedName, ddlConfig.getDroppedFieldNames());
            case ALTER_CHANGE: {
                // 跨源改名: 源端 RENAME 已生效, changedFieldNames 为 旧名->新名;
                // 生成目标方言 ALTER TABLE t RENAME COLUMN old TO new(MySQL 8+/PG/Kingbase/Oracle 语法一致)
                java.util.List<String> renames = new ArrayList<>();
                for (java.util.Map.Entry<String, String> e : ddlConfig.getChangedFieldNames().entrySet()) {
                    String newName = e.getValue();
                    boolean exists = false;
                    for (Field column : tableGroup.getSourceTable().getColumn()) {
                        if (column != null && StringUtil.equals(column.getName(), newName)) {
                            exists = true;
                            break;
                        }
                    }
                    if (exists) {
                        renames.add("ALTER TABLE " + qualifiedName + " RENAME COLUMN "
                                + database.buildWithQuotation(e.getKey()) + " TO " + database.buildWithQuotation(newName));
                    }
                }
                if (renames.isEmpty()) {
                    return StringUtil.EMPTY;
                }
                if (renames.size() > 1) {
                    logger.warn("跨源 DDL 批量改名仅执行第一条, 其余请人工处理: {}", renames);
                }
                return renames.get(0);
            }
            default:
                return StringUtil.EMPTY;
        }
    }

    private void updateTableColumn(Mapping mapping, String suffix, Table table) {
        boolean isSource = StringUtil.equals(ConnectorInstanceUtil.SOURCE_SUFFIX, suffix);
        DefaultConnectorServiceContext context = ConnectorServiceContextUtil.buildConnectorServiceContext(mapping, isSource);
        context.addTablePattern(table);

        List<MetaInfo> metaInfos = parserComponent.getMetaInfo(context);
        MetaInfo metaInfo = CollectionUtils.isEmpty(metaInfos) ? null : metaInfos.get(0);
        Assert.notNull(metaInfo, "无法获取连接器表信息:" + table.getName());
        table.setColumn(metaInfo.getColumn());
    }

    /**
     * 获取连接器配置
     */
    private ConnectorConfig getConnectorConfig(String connectorId) {
        Assert.hasText(connectorId, "Connector id can not be empty.");
        Connector conn = profileComponent.getConnector(connectorId);
        Assert.notNull(conn, "Connector can not be null.");
        return conn.getConfig();
    }

    private void printTraceInfo(WriterResponse response) {
        if (profileComponent.getSystemConfig().isEnablePrintTraceInfo() && StringUtil.isNotBlank(response.getTraceId())) {
            logger.info("traceId:{}, tableName:{}, event:{}, offset:{}, row:{}", response.getTraceId(), response.getTableName(), response.getEvent(), JsonUtil
                    .objToJson(response.getChangedOffset()), response.getDataList());
        }
    }

}