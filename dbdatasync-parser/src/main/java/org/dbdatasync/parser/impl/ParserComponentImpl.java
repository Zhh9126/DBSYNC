/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.parser.impl;

import org.dbdatasync.common.model.Result;
import org.dbdatasync.common.rsa.RsaManager;
import org.dbdatasync.common.util.BatchTaskUtil;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.SqlIdentifierValidator;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.common.util.TaskSplitUtil;
import org.dbdatasync.connector.base.ConnectorFactory;
import org.dbdatasync.parser.ParserComponent;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.event.FullRefreshEvent;
import org.dbdatasync.parser.model.FieldMapping;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Picker;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.model.Task;
import org.dbdatasync.parser.strategy.FlushStrategy;
import org.dbdatasync.parser.util.ConnectorInstanceUtil;
import org.dbdatasync.parser.util.ConvertUtil;
import org.dbdatasync.parser.util.MigrationRateLimiter;
import org.dbdatasync.parser.util.PickerUtil;
import org.dbdatasync.plugin.PluginFactory;
import org.dbdatasync.plugin.enums.ProcessEnum;
import org.dbdatasync.sdk.config.CommandConfig;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.connector.DefaultConnectorServiceContext;
import org.dbdatasync.sdk.connector.FullPluginContext;
import org.dbdatasync.sdk.connector.database.DatabaseConnectorInstance;
import org.dbdatasync.sdk.constant.ConnectorConstant;
import org.dbdatasync.sdk.model.ConnectorConfig;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.Filter;
import org.dbdatasync.sdk.model.MetaInfo;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.model.ValidateSyncTask;
import org.dbdatasync.sdk.plugin.PluginContext;
import org.dbdatasync.sdk.schema.SchemaResolver;
import org.dbdatasync.sdk.spi.ConnectorService;
import org.dbdatasync.sdk.util.PrimaryKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2019/9/29 22:38
 */
@Component
public class ParserComponentImpl implements ParserComponent {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private ConnectorFactory connectorFactory;

    @Resource
    private PluginFactory pluginFactory;

    @Resource
    private FlushStrategy flushStrategy;

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private RsaManager rsaManager;

    @Override
    public List<MetaInfo> getMetaInfo(DefaultConnectorServiceContext context) {
        String instanceId = ConnectorInstanceUtil.buildConnectorInstanceId(context.getMappingId(), context.getConnectorId(), context.getSuffix());
        ConnectorInstance connectorInstance = connectorFactory.connect(instanceId);
        return connectorFactory.getMetaInfo(connectorInstance, context);
    }

    @Override
    public Map<String, String> getCommand(Mapping mapping, TableGroup tableGroup) {
        return buildConnectorCommand(mapping.getId(), mapping.getSourceConnectorId(), mapping.getTargetConnectorId(),
                mapping.getSourceSchema(), mapping.getTargetSchema(), mapping.isForceUpdate(), tableGroup);
    }

    @Override
    public Map<String, String> getCommand(ValidateSyncTask task, TableGroup tableGroup) {
        return buildConnectorCommand(task.getId(), task.getSourceConnectorId(), task.getTargetConnectorId(),
                task.getSourceSchema(), task.getTargetSchema(), true, tableGroup);
    }

    /**
     * 根据驱动/任务 id 与表组构建连接器命令
     */
    private Map<String, String> buildConnectorCommand(String mappingId, String sourceConnectorId, String targetConnectorId,
                                                      String sourceSchema, String targetSchema, boolean forceUpdate, TableGroup tableGroup) {
        ConnectorConfig sConnConfig = getConnectorConfig(sourceConnectorId);
        ConnectorConfig tConnConfig = getConnectorConfig(targetConnectorId);
        Table sourceTable = tableGroup.getSourceTable();
        Table targetTable = tableGroup.getTargetTable();
        Table sTable = sourceTable.clone().setColumn(new ArrayList<>());
        Table tTable = targetTable.clone().setColumn(new ArrayList<>());
        List<FieldMapping> fieldMapping = tableGroup.getFieldMapping();
        List<Filter> targetFilter = getTargetFilter(tableGroup, fieldMapping);
        if (!CollectionUtils.isEmpty(fieldMapping)) {
            fieldMapping.forEach(m -> {
                if (null != m.getSource()) {
                    sTable.getColumn().add(m.getSource());
                }
                if (null != m.getTarget()) {
                    tTable.getColumn().add(m.getTarget());
                }
            });
        }
        String sourceInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mappingId, sourceConnectorId, ConnectorInstanceUtil.SOURCE_SUFFIX);
        String targetInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mappingId, targetConnectorId, ConnectorInstanceUtil.TARGET_SUFFIX);
        ConnectorInstance sourceInstance = connectorFactory.connect(sourceInstanceId);
        ConnectorInstance targetInstance = connectorFactory.connect(targetInstanceId);
        final CommandConfig sourceConfig = new CommandConfig(sConnConfig.getConnectorType(), sourceSchema, sTable, sourceInstance, tableGroup.getFilter());
        final CommandConfig targetConfig = new CommandConfig(tConnConfig.getConnectorType(), targetSchema, tTable, targetInstance, targetFilter);
        targetConfig.setForceUpdate(forceUpdate);
        return connectorFactory.getCommand(sourceConfig, targetConfig);
    }

    @Override
    public void execute(Task task, Mapping mapping, TableGroup tableGroup, Executor executor) {
        final String metaId = task.getId();
        final String sourceConnectorId = mapping.getSourceConnectorId();
        final String targetConnectorId = mapping.getTargetConnectorId();

        ConnectorConfig sConfig = getConnectorConfig(sourceConnectorId);
        Assert.notNull(sConfig, "数据源配置不能为空.");
        ConnectorConfig tConfig = getConnectorConfig(targetConnectorId);
        Assert.notNull(tConfig, "目标源配置不能为空.");
        TableGroup group = PickerUtil.mergeTableGroupConfig(mapping, tableGroup);
        Map<String, String> command = group.getCommand();
        Assert.notEmpty(command, "执行命令不能为空.");
        List<FieldMapping> fieldMapping = group.getFieldMapping();
        // 限速器：从映射参数读取（默认 0 不限速，存量任务零影响），整表/各分片共享同一实例
        MigrationRateLimiter limiter = MigrationRateLimiter.fromParams(mapping.getParams());
        // 分片并发读前置判定：满足条件时走分片分支（兼容旧逻辑，不满足自动降级回串行）
        if (splitSupported(mapping, command, group)) {
            executeSliced(task, mapping, group, command, executor, limiter);
            return;
        }
        executeCommon(task, mapping, group, command, fieldMapping, executor, limiter);
    }

    /**
     * 全量分片前置判定：仅当满足以下全部条件时启用分片并发读，否则走原串行路径：
     * <ol>
     *     <li>游标分页 SQL 可用（QUERY_CURSOR 非空），否则无法按主键稳定分页；</li>
     *     <li>映射配置了分片键 fullSplitKey 与分片数 fullSplitCount&gt;0（默认关闭，存量任务零影响）；</li>
     *     <li>源表包含该分片键列（避免 SQL 引用不存在列）。</li>
     * </ol>
     */
    private boolean splitSupported(Mapping mapping, Map<String, String> command, TableGroup group) {
        if (mapping == null || mapping.getParams() == null || StringUtil.isBlank(command.get(ConnectorConstant.OPERTION_QUERY_CURSOR))) {
            return false;
        }
        String splitKey = mapping.getParams().get(ConfigConstant.FULL_SPLIT_KEY);
        String splitCount = mapping.getParams().get(ConfigConstant.FULL_SPLIT_COUNT);
        if (StringUtil.isBlank(splitKey) || StringUtil.isBlank(splitCount) || !splitCount.matches("\\d+")) {
            return false;
        }
        Table sourceTable = group.getSourceTable();
        return sourceTable != null
                && !CollectionUtils.isEmpty(sourceTable.getColumn())
                && sourceTable.getColumn().stream().anyMatch(f -> splitKey.equalsIgnoreCase(f.getName()));
    }

    /**
     * 规范化分片数：非法/非数字返回 1（退化为串行）；上限 1024 防配置错误导致资源爆炸。
     */
    private int normalizeSplitCount(String splitCount) {
        if (StringUtil.isBlank(splitCount) || !splitCount.matches("\\d+")) {
            return 1;
        }
        try {
            long v = Long.parseLong(splitCount);
            if (v <= 1) {
                return 1;
            }
            return (int) Math.min(v, 1024);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 分片并发读主流程：将单表按 splitKey 值域切分为 splitCount 个左闭右开区间，
     * 每个区间一个独立子上下文（继承 command/映射/插件，注入 [start,end) 过滤条件），
     * 并发执行各自的游标循环；共享 task 启停信号与 meta 计数（flush 原子累加，天然线程安全）。
     * <p>任何子分片异常都将聚合后上抛，保证失败可见。</p>
     *
     * @param task     父任务（子分片共享其 isRunning 信号）
     * @param mapping  映射配置
     * @param group    合并后的表映射（含 command）
     * @param command  执行命令
     * @param executor 外部调度执行器（用于 flush / 批写）
     */
    private void executeSliced(Task task, Mapping mapping, TableGroup group, Map<String, String> command, Executor executor, MigrationRateLimiter limiter) {
        final String splitKey = mapping.getParams().get(ConfigConstant.FULL_SPLIT_KEY);
        final int splitCount = normalizeSplitCount(mapping.getParams().get(ConfigConstant.FULL_SPLIT_COUNT));
        final String metaId = task.getId();

        List<FieldMapping> fieldMappings = group.getFieldMapping();
        Field splitField = group.getSourceTable().getColumn().stream()
                .filter(f -> splitKey.equalsIgnoreCase(f.getName()))
                .findFirst()
                .orElse(null);
        Assert.notNull(splitField, String.format("分片键[%s]在源表中不存在.", splitKey));

        // 1、探测源端 splitKey 值域（MIN/MAX），探测失败或空表降级串行
        FullyQualifiedSplitRange range = probeSplitRange(task, mapping, group, command, splitKey);
        if (range == null || range.max <= range.min) {
            logger.warn("分片值域探测失败或空表，降级为串行:{} 分片键[{}]", metaId, splitKey);
            executeCommon(task, mapping, group, group.getCommand(), fieldMappings, executor, limiter);
            return;
        }

        // 2、按值域切分（左闭右开，最后一片闭合到 max）
        long[][] intervals = splitHalfOpenRange(range.min, range.max, splitCount);
        if (intervals.length <= 1) {
            logger.info("分片并发读降级为串行:{} 值域不足2片", metaId);
            executeCommon(task, mapping, group, group.getCommand(), fieldMappings, executor, limiter);
            return;
        }
        logger.info("分片并发读:{} 表[{}] 分片键[{}] 值域[{}~{}] 分片数[{}]", metaId,
                group.getSourceTable().getName(), splitKey, range.min, range.max, intervals.length);

        // 3、并发执行各分片（共享 task/meta，flush 与 meta 计数天然线程安全）
        runSlices(task, mapping, group, command, fieldMappings, splitKey, intervals, executor, limiter);
    }

    /**
     * 探测分片键值域。
     * <p>复用 command 中 QUERY_COUNT 的「FROM 子句」（含方言限定表名与 queryFilter 条件），
     * 把 {@code COUNT(*)} 替换为 {@code MIN(x), MAX(x)}，避免重复拼表名/条件且天然支持映射过滤条件。</p>
     *
     * @return 探测结果（min/max）；执行失败 / SQL 不含 COUNT(*) 模式 / 结果为 null 时返回 null
     */
    private FullyQualifiedSplitRange probeSplitRange(Task task, Mapping mapping, TableGroup group, Map<String, String> command, String splitKey) {
        String countSql = command.get(ConnectorConstant.OPERTION_QUERY_COUNT);
        if (StringUtil.isBlank(countSql)) {
            return null;
        }
        final String countToken = "COUNT(*)";
        int idx = countSql.toUpperCase().indexOf(countToken);
        if (idx < 0) {
            return null;
        }
        // 列名经白名单校验后原样拼接（splitKey 已在 splitSupported 确认是源表元数据列）
        SqlIdentifierValidator.validateIdentifier(splitKey);
        final String minMaxSql = countSql.substring(0, idx) + "MIN(" + splitKey + "), MAX(" + splitKey + ")"
                + countSql.substring(idx + countToken.length());
        final String sourceInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mapping.getId(), mapping.getSourceConnectorId(), ConnectorInstanceUtil.SOURCE_SUFFIX);
        try {
            ConnectorInstance sourceInstance = connectorFactory.connect(sourceInstanceId);
            Object[] minMax = ((DatabaseConnectorInstance) sourceInstance).execute(databaseTemplate -> {
                java.util.List<Map<String, Object>> rows = databaseTemplate.queryForList(minMaxSql);
                if (CollectionUtils.isEmpty(rows)) {
                    return null;
                }
                Map<String, Object> row = rows.get(0);
                Object min = row.values().toArray()[0];
                Object max = row.values().toArray()[1];
                if (min == null || max == null) {
                    return null;
                }
                return new Object[]{toLong(min), toLong(max)};
            });
            if (minMax == null || minMax.length < 2) {
                return null;
            }
            return new FullyQualifiedSplitRange((Long) minMax[0], (Long) minMax[1]);
        } catch (Exception e) {
            logger.warn("分片值域探测SQL执行失败，降级串行:{} 原因:{}", task.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 把 MIN/MAX 探测结果安全转为 long：支持 Number（含 BigInteger/BigDecimal）与字符串。
     */
    private static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof java.math.BigDecimal) {
            return ((java.math.BigDecimal) value).longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }

    /**
     * 并发执行全量分片读：每分片一个独立游标循环，共享父 task 启停信号。
     * 任一子分片失败即取消中止父任务并上抛首个异常，其余分片尽力退出。
     */
    private void runSlices(Task task, Mapping mapping, TableGroup group, Map<String, String> command,
                           List<FieldMapping> fieldMappings, String splitKey, long[][] intervals, Executor executor,
                           MigrationRateLimiter limiter) {
        long t0 = System.nanoTime();
        final int count = intervals.length;
        final int poolSize = Math.min(count, mapping.getThreadNum() > 0 ? mapping.getThreadNum() : count);
        final CountDownLatch latch = new CountDownLatch(count);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "full-split-" + task.getId() + "-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < count; i++) {
            final long[] interval = intervals[i];
            pool.execute(() -> {
                // 每个分片独立子任务：独立 cursors/pageIndex，共享父任务启停
                Task sliceTask = task.createTableTask(group.getId());
                try {
                    executeSlice(sliceTask, mapping, group, command, fieldMappings, splitKey, interval[0], interval[1], executor, limiter);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                    task.stop();
                    logger.error(String.format("分片执行失败[%s] 区间[%d, %d):%s", task.getId(), interval[0], interval[1]), e);
                } finally {
                    latch.countDown();
                }
            });
        }
        pool.shutdown();
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.stop();
        }
        Throwable error = failure.get();
        if (error != null) {
            throw error instanceof RuntimeException ? (RuntimeException) error : new RuntimeException(error);
        }
        long costMs = (System.nanoTime() - t0) / 1_000_000L;
        long avgMs = count > 0 ? costMs / count : 0;
        logger.info("分片并发读完成:{} 共{}个分片, 耗时{}ms / 分片数{} / 平均{}ms每片", task.getId(), count, costMs, count, avgMs);
    }

    /**
     * 单个分片的全量游标循环：与串行 executeCommon 的循环体语义一致，差异仅在：
     * <ol>
     *     <li>SOURCE command 克隆后注入分片范围条件（splitKey &gt;= start AND splitKey &lt; end）；</li>
     *     <li>每次游标读取的 args 前缀追加 [start, end]（与注入 SQL 的占位符顺序对齐）；</li>
     *     <li>子上下文独立，不共享 cursors/pageIndex 可变状态。</li>
     * </ol>
     */
    private void executeSlice(Task task, Mapping mapping, TableGroup group, Map<String, String> command,
                              List<FieldMapping> fieldMapping, String splitKey, long start, long end, Executor executor,
                              MigrationRateLimiter limiter) {
        final String metaId = task.getId();
        final String sourceConnectorId = mapping.getSourceConnectorId();
        final String targetConnectorId = mapping.getTargetConnectorId();
        final String sTableName = group.getSourceTable().getName();
        final String tTableName = group.getTargetTable().getName();
        Assert.notEmpty(fieldMapping, String.format("数据源表[%s]同步到目标源表[%s], 映射关系不能为空.", sTableName, tTableName));
        Picker picker = new Picker(group);

        // 分片专用 SOURCE command：克隆并注入范围条件到 QUERY_CURSOR SQL
        Map<String, String> sliceCommand = new HashMap<>(command);
        String cursorSql = command.get(ConnectorConstant.OPERTION_QUERY_CURSOR);
        boolean enableCursor = StringUtil.isNotBlank(cursorSql);
        if (enableCursor) {
            String injected = injectRangeCondition(cursorSql, splitKey, start, end);
            sliceCommand.put(ConnectorConstant.OPERTION_QUERY_CURSOR, injected);
            // QUERY（第一页非游标）与 QUERY_COUNT 一并注入，保证总览页与计数口径一致
            String querySql = command.get(ConnectorConstant.OPERTION_QUERY);
            if (StringUtil.isNotBlank(querySql)) {
                sliceCommand.put(ConnectorConstant.OPERTION_QUERY, injectRangeCondition(querySql, splitKey, start, end));
            }
            String countSql = command.get(ConnectorConstant.OPERTION_QUERY_COUNT);
            if (StringUtil.isNotBlank(countSql)) {
                sliceCommand.put(ConnectorConstant.OPERTION_QUERY_COUNT, injectRangeCondition(countSql, splitKey, start, end));
            }
        }

        List<String> primaryKeys = getPrimaryKeysForCursor(sliceCommand, group.getSourceTable(), enableCursor);
        final FullPluginContext context = new FullPluginContext();
        String sourceInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mapping.getId(), sourceConnectorId, ConnectorInstanceUtil.SOURCE_SUFFIX);
        String targetInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mapping.getId(), targetConnectorId, ConnectorInstanceUtil.TARGET_SUFFIX);
        context.setSourceConnectorInstance(connectorFactory.connect(sourceInstanceId));
        context.setTargetConnectorInstance(connectorFactory.connect(targetInstanceId));
        context.setEvent(ConnectorConstant.OPERTION_INSERT);
        context.setCommand(sliceCommand);
        context.setBatchSize(mapping.getBatchNum());
        context.setPlugin(group.getPlugin());
        context.setPluginExtInfo(group.getPluginExtInfo());
        context.setForceUpdate(mapping.isForceUpdate());
        context.setSourceTable(group.getSourceTable());
        context.setTargetTable(group.getTargetTable());
        context.setTargetFields(picker.getTargetFields());
        context.setSupportedCursor(enableCursor);
        context.setPageSize(mapping.getReadNum());
        setRsaConfig(context);
        ConnectorService sourceConnector = connectorFactory.getConnectorService(context.getSourceConnectorInstance().getConfig());
        ConnectorService targetConnector = connectorFactory.getConnectorService(context.getTargetConnectorInstance().getConfig());
        picker.setSourceResolver(sourceConnector.getSchemaResolver());
        Map<String, Field> targetFieldMap = picker.getTargetFieldMap();
        pluginFactory.process(context, ProcessEnum.BEFORE);

        // 分片范围参数前缀（每次读取必须存在于 args 起始位，与注入 SQL 占位符顺序一致）
        final List<Object> rangePrefix = Arrays.asList(start, end);

        for (; ; ) {
            if (!task.isRunning()) {
                logger.warn("任务被中止:{}", metaId);
                break;
            }
            // 1、获取数据源数据（分片前缀 + 游标参数）
            List<Object> args = new ArrayList<>(rangePrefix);
            context.setArgs(args);
            context.setCursors(task.getCursors());
            context.setPageIndex(task.getPageIndex());
            Result reader = connectorFactory.reader(context);
            List<Map> source = reader.getSuccessData();
            if (CollectionUtils.isEmpty(source)) {
                logger.info("完成分片全量任务:{} 区间[{}~{}) [{}] >> [{}]", metaId, start, end, sTableName, tTableName);
                break;
            }

            // 批次级限速：读取后、写入前申请本秒「记录数 + 估算字节数」配额（默认 0 时零开销、不改执行顺序）
            limiter.acquire(source.size(), estimateBytes(source));

            // 2、映射字段
            List<Map> target = picker.pickTargetData(source);

            // 3、参数转换
            ConvertUtil.convert(group.getConvert(), target);

            // 4、插件转换
            context.setSourceList(source);
            context.setTargetList(target);
            pluginFactory.process(context, ProcessEnum.CONVERT);

            // 5、写入目标源
            Result result = writeBatch(context, executor);

            // 6、更新结果
            task.setPageIndex(task.getPageIndex() + 1);
            task.setCursors(PrimaryKeyUtil.getLastCursors(source, primaryKeys));
            result.setTableGroupId(group.getId());
            result.setTargetTableGroupName(tTableName);
            flush(task, result, targetConnector.getSchemaResolver(), targetFieldMap);

            // 7、同步完成后通知插件做后置处理
            pluginFactory.process(context, ProcessEnum.AFTER);

            // 8、释放本批数据引用
            if (context.getSourceList() != null) {
                context.getSourceList().clear();
            }
            if (context.getTargetList() != null) {
                context.getTargetList().clear();
            }
        }
    }

    /**
     * 向已有只读查询 SQL 注入单值分片范围条件：
     * <pre>
     * SELECT ... FROM "t" [WHERE &lt;原条件&gt;] [ORDER BY ...] [LIMIT ?,?]
     *  → SELECT ... FROM "t" [WHERE (&lt;splitKey&gt; &gt;= ? AND &lt;splitKey&gt; &lt; ?) AND &lt;原条件&gt;] [ORDER BY ...] [LIMIT ?,?]
     * </pre>
     * 无 WHERE 时在 FROM 子句后新增 WHERE。注入后的参数占位符顺序为 [start, end, ...原参数]。
     * <p>包级可见以便单元测试。</p>
     *
     * @param sql      原 SQL（QUERY_CURSOR / QUERY / QUERY_COUNT）
     * @param splitKey 分片键（白名单校验后拼接，防注入）
     * @param start    区间起点（含）
     * @param end      区间终点（不含）
     * @return 注入后的 SQL
     */
    String injectRangeCondition(String sql, String splitKey, long start, long end) {
        SqlIdentifierValidator.validateIdentifier(splitKey);
        String range = "(" + splitKey + " >= ? AND " + splitKey + " < ?)";
        String upper = sql.toUpperCase();
        int whereIdx = upper.indexOf(" WHERE ");
        if (whereIdx >= 0) {
            return sql.substring(0, whereIdx + " WHERE ".length())
                    + range + " AND "
                    + sql.substring(whereIdx + " WHERE ".length());
        }
        // 无 WHERE：在 FROM 子句后（ORDER BY / LIMIT 之前）插入 WHERE
        int orderIdx = upper.indexOf(" ORDER BY ");
        int limitIdx = upper.indexOf(" LIMIT ");
        int insertPos;
        if (orderIdx >= 0) {
            insertPos = orderIdx;
        } else if (limitIdx >= 0) {
            insertPos = limitIdx;
        } else {
            return sql + " WHERE " + range;
        }
        return sql.substring(0, insertPos) + " WHERE " + range + sql.substring(insertPos);
    }

    /**
     * 把 [min, max] 值域切分为 count 个左闭右开区间 [start, end)。
     * end 为开区间，最后一片取 max+1 以覆盖最大值。count &lt;= 0 或空值域返回空数组。
     * <p>包级可见以便单元测试。</p>
     */
    static long[][] splitHalfOpenRange(long min, long max, int count) {
        if (max < min || count <= 0) {
            return new long[0][];
        }
        int n = Math.max(1, count);
        long total = max - min + 1;
        long step = Math.max(1, total / n);
        java.util.List<long[]> ranges = new ArrayList<>(n);
        long start = min;
        for (int i = 0; i < n && start <= max; i++) {
            long end;
            if (i == n - 1) {
                end = max == Long.MAX_VALUE ? max : max + 1;
            } else {
                end = start + step;
                if (end > max) {
                    end = max;
                }
                if (end <= start) {
                    end = start + 1;
                }
            }
            ranges.add(new long[]{start, end});
            start = end;
        }
        return ranges.toArray(new long[0][]);
    }

    /** 分片值域结果（左闭右闭） */
    private static final class FullyQualifiedSplitRange {
        private final long min;
        private final long max;

        private FullyQualifiedSplitRange(long min, long max) {
            this.min = min;
            this.max = max;
        }
    }

    /**
     * 原全量同步主流程（单游标串行，逻辑与原 execute 一致，供分片降级与默认路径使用）。
     */
    private void executeCommon(Task task, Mapping mapping, TableGroup group, Map<String, String> command,
                               List<FieldMapping> fieldMapping, Executor executor, MigrationRateLimiter limiter) {
        final String metaId = task.getId();
        final String sourceConnectorId = mapping.getSourceConnectorId();
        final String targetConnectorId = mapping.getTargetConnectorId();
        final String sTableName = group.getSourceTable().getName();
        final String tTableName = group.getTargetTable().getName();
        Assert.notEmpty(fieldMapping, String.format("数据源表[%s]同步到目标源表[%s], 映射关系不能为空.", sTableName, tTableName));
        // 获取同步字段
        Picker picker = new Picker(group);
        // 游标分页时使用与构建 QUERY_CURSOR 一致的主键列表，避免 findTablePrimaryKeys 返回表上未参与游标的主键（如 id）导致 getLastCursors 多取游标值、参数个数与 SQL 占位符不一致
        boolean enableCursor = StringUtil.isNotBlank(command.get(ConnectorConstant.OPERTION_QUERY_CURSOR));
        List<String> primaryKeys = getPrimaryKeysForCursor(command, group.getSourceTable(), enableCursor);
        final FullPluginContext context = new FullPluginContext();

        String sourceInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mapping.getId(), sourceConnectorId, ConnectorInstanceUtil.SOURCE_SUFFIX);
        String targetInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(mapping.getId(), targetConnectorId, ConnectorInstanceUtil.TARGET_SUFFIX);
        context.setSourceConnectorInstance(connectorFactory.connect(sourceInstanceId));
        context.setTargetConnectorInstance(connectorFactory.connect(targetInstanceId));
        context.setEvent(ConnectorConstant.OPERTION_INSERT);
        context.setCommand(command);
        context.setBatchSize(mapping.getBatchNum());
        context.setPlugin(group.getPlugin());
        context.setPluginExtInfo(group.getPluginExtInfo());
        context.setForceUpdate(mapping.isForceUpdate());
        context.setSourceTable(group.getSourceTable());
        context.setTargetTable(group.getTargetTable());
        context.setTargetFields(picker.getTargetFields());
        context.setSupportedCursor(enableCursor);
        context.setPageSize(mapping.getReadNum());
        setRsaConfig(context);
        ConnectorService sourceConnector = connectorFactory.getConnectorService(context.getSourceConnectorInstance().getConfig());
        ConnectorService targetConnector = connectorFactory.getConnectorService(context.getTargetConnectorInstance().getConfig());
        picker.setSourceResolver(sourceConnector.getSchemaResolver());
        Map<String, Field> targetFieldMap = picker.getTargetFieldMap();
        // 0、插件前置处理
        pluginFactory.process(context, ProcessEnum.BEFORE);

        for (; ; ) {
            if (!task.isRunning()) {
                logger.warn("任务被中止:{}", metaId);
                break;
            }

            // 1、获取数据源数据
            context.setArgs(new ArrayList<>());
            context.setCursors(task.getCursors());
            context.setPageIndex(task.getPageIndex());
            Result reader = connectorFactory.reader(context);
            List<Map> source = reader.getSuccessData();
            if (CollectionUtils.isEmpty(source)) {
                logger.info("完成全量同步任务:{}, [{}] >> [{}]", metaId, sTableName, tTableName);
                break;
            }

            // 批次级限速：读取后、写入前申请本秒「记录数 + 估算字节数」配额（默认 0 时零开销、不改执行顺序）
            limiter.acquire(source.size(), estimateBytes(source));

            // 2、映射字段
            List<Map> target = picker.pickTargetData(source);

            // 3、参数转换
            ConvertUtil.convert(group.getConvert(), target);

            // 4、插件转换
            context.setSourceList(source);
            context.setTargetList(target);
            pluginFactory.process(context, ProcessEnum.CONVERT);

            // 5、写入目标源
            Result result = writeBatch(context, executor);

            // 6、更新结果
            task.setPageIndex(task.getPageIndex() + 1);
            task.setCursors(PrimaryKeyUtil.getLastCursors(source, primaryKeys));
            result.setTableGroupId(group.getId());
            result.setTargetTableGroupName(tTableName);
            flush(task, result, targetConnector.getSchemaResolver(), targetFieldMap);

            // 7、同步完成后通知插件做后置处理
            pluginFactory.process(context, ProcessEnum.AFTER);

            // 8、释放本批数据引用，避免 context 长期持有大 List（如 10000 条），减轻 LinkedList 保留内存
            if (context.getSourceList() != null) {
                context.getSourceList().clear();
            }
            if (context.getTargetList() != null) {
                context.getTargetList().clear();
            }
        }
    }

    /**
     * 粗略估算一批数据的字节数：逐行累加 {@code Map.toString().length()}（复刻 engine/Record.estimateBytes 思路）。
     * <p>仅用于限速字节维度，估算失败返回 0（该维度不累加），绝不影响主链路。</p>
     */
    private static long estimateBytes(List<Map> source) {
        try {
            long size = 0;
            for (Map row : source) {
                if (row != null) {
                    size += row.toString().length();
                }
            }
            return size;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public Result writeBatch(PluginContext context, Executor executor) {
        Result result = new Result();
        // 终止同步数据到目标源库
        if (context.isTerminated()) {
            result.getSuccessData().addAll(context.getTargetList());
            return result;
        }

        int batchSize = context.getBatchSize();
        List<Map> targetList = context.getTargetList();
        int total = targetList.size();
        // Wave24：线程池线程不继承 ThreadLocal，捕获父线程任务 ID 后在子任务内显式 set/clear
        final String taskId = org.dbdatasync.sdk.context.TaskContextHolder.currentTaskId();
        final org.dbdatasync.sdk.checkpoint.CheckpointRecord pendingCheckpoint =
                org.dbdatasync.sdk.checkpoint.TargetTxCheckpoint.current();
        // 单次任务（当前线程，Holder 已由分发方设置）
        if (total <= batchSize) {
            return connectorFactory.writer(context);
        }

        // 批量任务, 拆分
        int taskSize = (total + batchSize - 1) / batchSize;
        CountDownLatch latch = new CountDownLatch(taskSize);
        TaskSplitUtil.split(targetList, batchSize, batch -> {
            try {
                PluginContext tmpContext = (PluginContext) context.clone();
                tmpContext.setTargetList(batch);
                executor.execute(() -> {
                    // Wave24：把任务上下文带进线程池子任务，慢 SQL 观察点才能拿到 taskId
                    if (taskId != null) {
                        org.dbdatasync.sdk.context.TaskContextHolder.set(taskId);
                    }
                    // Wave24：待提交位点随子任务透传，保证大事务也在目标库事务内写位点表
                    if (pendingCheckpoint != null) {
                        org.dbdatasync.sdk.checkpoint.TargetTxCheckpoint.begin(pendingCheckpoint);
                    }
                    try {
                        Result w = connectorFactory.writer(tmpContext);
                        result.addSuccessData(w.getSuccessData());
                        result.addFailData(w.getFailData());
                        result.getError().append(w.getError());
                        // Wave24：逐行失败分类必须随子批结果合并，否则大事务路径分类丢失
                        result.getRowFailures().putAll(w.getRowFailures());
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                        // 子批整体异常时旧实现只打日志，整批既不进 successData 也不进 failData，
                        // 数据会静默丢失且无法进入失败队列。这里按失败明细兜底，保证可观测、可重推。
                        result.addFailData(batch);
                        result.getError().append("[BATCH_FAILED] ").append(e.getMessage())
                                .append(" ").append(org.dbdatasync.sdk.retry.WriteFailureTag.describe(e))
                                .append(System.lineSeparator());
                    } finally {
                        org.dbdatasync.sdk.context.TaskContextHolder.clear();
                        org.dbdatasync.sdk.checkpoint.TargetTxCheckpoint.end();
                        latch.countDown();
                    }
                });
            } catch (CloneNotSupportedException e) {
                logger.error(e.getMessage(), e);
                result.addFailData(batch);
                result.getError().append("[BATCH_CLONE_FAILED] ").append(e.getMessage())
                        .append(System.lineSeparator());
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            logger.error(e.getMessage());
        }
        return result;
    }

    private void setRsaConfig(FullPluginContext context) {
        if (profileComponent.getSystemConfig().isEnableOpenAPI()) {
            context.setRsaManager(rsaManager);
            context.setRsaConfig(profileComponent.getSystemConfig().getRsaConfig());
        }
    }

    /**
     * 更新缓存
     */
    private void flush(Task task, Result result, SchemaResolver targetSchemaResolver, Map<String, Field> targetFieldMap) {
        result.setMetaId(task.getId());
        result.setEvent(ConnectorConstant.OPERTION_INSERT);
        flushStrategy.flushFullData(result, targetSchemaResolver, targetFieldMap);

        // 发布刷新事件给FullExtractor
        task.setEndTime(Instant.now().toEpochMilli());
        applicationContext.publishEvent(new FullRefreshEvent(applicationContext, task));
    }

    /**
     * 游标分页时使用与构建 QUERY_CURSOR 一致的主键列表；非游标或未配置时使用表主键。
     * 避免 findTablePrimaryKeys(sourceTable) 返回表上全部主键（含未参与游标的 id）导致 getLastCursors 多取游标值、参数与 SQL 占位符不一致。
     *
     * @param command      执行命令（含 CURSOR_PK_NAMES 时表示游标实际使用的主键）
     * @param sourceTable  源表
     * @param enableCursor 是否支持游标
     * @return 用于 getLastCursors 的主键名列表
     */
    private List<String> getPrimaryKeysForCursor(Map<String, String> command, Table sourceTable, boolean enableCursor) {
        if (enableCursor) {
            String cursorPkNames = command.get(ConnectorConstant.CURSOR_PK_NAMES);
            if (StringUtil.isNotBlank(cursorPkNames)) {
                return Arrays.stream(cursorPkNames.split(StringUtil.COMMA)).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            }
        }
        // 不支持游标查询，或非结构化连接器场景
        return PrimaryKeyUtil.findTablePrimaryKeys(sourceTable);
    }

    /**
     * 获取连接配置
     *
     * @param connectorId
     * @return
     */
    private ConnectorConfig getConnectorConfig(String connectorId) {
        return profileComponent.getConnector(connectorId).getConfig();
    }

    /**
     * 根据源条件 组装目标表条件
     */
    private static List<Filter> getTargetFilter(TableGroup tableGroup, List<FieldMapping> fieldMapping) {
        List<Filter> filter = tableGroup.getFilter();
        List<Filter> targetFilter = new ArrayList<>();
        if (!CollectionUtils.isEmpty(filter)) {
            filter.forEach(f -> {
                Field matchedField = fieldMapping.stream()
                        .filter(m -> m.getSource().getName().equals(f.getName()))
                        .map(FieldMapping::getTarget)                 // 获取目标字段
                        .findFirst()
                        .orElse(null);
                if (matchedField != null) {
                    Filter tmp = new Filter();
                    tmp.setName(matchedField.getName());
                    tmp.setFilter(f.getFilter());
                    tmp.setValue(f.getValue());
                    tmp.setOperation(f.getOperation());
                    targetFilter.add(tmp);
                }
            });
        }
        return targetFilter;
    }

}