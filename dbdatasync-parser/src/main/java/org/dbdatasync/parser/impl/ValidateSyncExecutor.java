package org.dbdatasync.parser.impl;

import org.dbdatasync.common.enums.CommonTaskStatusEnum;
import org.dbdatasync.common.enums.TaskLevelEnum;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.common.util.UUIDUtil;
import org.dbdatasync.connector.base.ConnectorFactory;
import org.dbdatasync.parser.ConnectorProfile;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.TaskDetailProfile;
import org.dbdatasync.parser.TaskProfile;
import org.dbdatasync.parser.model.Connector;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.model.TaskDetailQuery;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.connector.database.DatabaseConnectorInstance;
import org.dbdatasync.sdk.connector.database.DatabaseTemplate;
import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.model.ConnectorConfig;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.MetaIncrement;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.model.ValidateSyncTask;
import org.dbdatasync.sdk.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订正校验任务执行引擎：连接源/目标库，执行表结构、行数据、索引比对，
 * 并在 {@code enableSync} 或手动订正时把差异写回目标库。
 * <p>结果写入运行明细分表（{@code DBDataSync_task_detail_{taskId}}）与
 * 明细级/任务级 Meta（{@code DBDataSync_meta}）。
 */
@Component
public class ValidateSyncExecutor {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /** 差异明细最大保留条数，防止明细分表 DATA 过大 */
    private static final int MAX_CONTENT_ITEMS = 200;

    /** 单次手动订正最大条数（与前端"手动订正(最多100条)"文案对齐） */
    private static final int MAX_REPAIR_ITEMS = 100;

    /** 单页读取条数上限 */
    private static final int MAX_PAGE_SIZE = 5000;

    /** 反向扫描（目标多余行）时的行数上限，超过则跳过反向扫描 */
    private static final long MAX_REVERSE_SCAN_ROWS = 200_000L;

    /** 明细类型：行数据（与前端 typeTextMap 对齐） */
    public static final String CHECK_TYPE_ROW_DATA = "rowData";

    /** 明细类型：表结构 */
    public static final String CHECK_TYPE_TABLE_SCHEMA = "tableSchema";

    /** 明细类型：索引 */
    public static final String CHECK_TYPE_INDEX = "index";

    private static final java.util.regex.Pattern DATETIME_PATTERN =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?");
    private static final java.util.regex.Pattern DATE_PATTERN =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final java.util.regex.Pattern TIME_PATTERN =
            java.util.regex.Pattern.compile("\\d{2}:\\d{2}:\\d{2}");

    @Resource
    private TaskProfile taskProfile;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private ConnectorProfile connectorProfile;

    @Resource
    private TaskDetailProfile taskDetailProfile;

    @Resource
    private ConnectorFactory connectorFactory;

    @Resource
    private StorageService storageService;

    /** 请求停止的任务集合 */
    private final Set<String> stopping = ConcurrentHashMap.newKeySet();

    public void requestStop(String taskId) {
        stopping.add(taskId);
    }

    public boolean isStopping(String taskId) {
        return stopping.contains(taskId);
    }

    // ------------------------------------------------------------ 主流程

    /**
     * 执行一轮订正校验（同步阻塞，调用方负责放线程）。
     */
    public void run(String taskId) {
        Assert.hasText(taskId, "任务ID不能为空");
        ValidateSyncTask task = taskProfile.getTask(taskId, ValidateSyncTask.class);
        Assert.notNull(task, "任务不存在");
        stopping.remove(taskId);

        List<TableGroup> groups = loadTableGroups(taskId);
        Meta taskMeta = ensureMeta(taskId, false);
        taskMeta.setState(CommonTaskStatusEnum.RUNNING.getCode());
        metaProfile.updateMeta(taskMeta);

        long sumSource = 0L;
        long sumTarget = 0L;
        long sumDiff = 0L;
        long sumFixed = 0L;
        boolean stopped = false;
        try {
            if (CollectionUtils.isEmpty(groups)) {
                logger.warn("订正校验任务 [{}] 无表映射，本轮结束", taskId);
            }
            for (TableGroup tg : groups) {
                if (isStopping(taskId)) {
                    stopped = true;
                    break;
                }
                long[] counters = validateTableGroup(task, tg, taskId);
                sumSource += counters[0];
                sumTarget += counters[1];
                sumDiff += counters[2];
                sumFixed += counters[3];
            }
        } finally {
            taskMeta = ensureMeta(taskId, false);
            taskMeta.setState(stopped ? CommonTaskStatusEnum.READY.getCode() : CommonTaskStatusEnum.DONE.getCode());
            taskMeta.setTotal(new java.util.concurrent.atomic.AtomicLong(sumSource));
            metaProfile.updateMeta(taskMeta);
            if (sumDiff > 0 || sumFixed > 0) {
                metaProfile.incrementMeta(MetaIncrement.of(taskMeta.getId())
                        .diff(sumDiff).fixed(sumFixed));
            }
            stopping.remove(taskId);
            logger.info("订正校验任务 [{}] 本轮结束: sourceTotal={}, targetTotal={}, diff={}, fixed={}",
                    taskId, sumSource, sumTarget, sumDiff, sumFixed);
        }
    }

    /**
     * 校验单个表映射：按校验类型分别写明细行（rowData/tableSchema/index），
     * 表明细 Meta 汇总三类结果。返回 [sourceTotal, targetTotal, diff, fixed]。
     */
    private long[] validateTableGroup(ValidateSyncTask task, TableGroup tg, String taskId) {
        String sourceTable = tableName(tg.getSourceTable());
        String targetTable = tableName(tg.getTargetTable());
        Meta meta = ensureMeta(tg.getId(), true);
        meta.setState(CommonTaskStatusEnum.RUNNING.getCode());
        metaProfile.updateMeta(meta);

        long sourceTotal = 0L;
        long targetTotal = 0L;
        long schemaDiff = 0L;
        long rowDiff = 0L;
        long rowFixed = 0L;
        long indexDiff = 0L;
        String error = StringUtil.EMPTY;
        List<Map<String, Object>> schemaContent = new ArrayList<>();
        List<Map<String, Object>> rowContent = new ArrayList<>();
        List<Map<String, Object>> indexContent = new ArrayList<>();
        DatabaseConnectorInstance source = null;
        DatabaseConnectorInstance target = null;
        try {
            source = connect(task.getSourceConnectorId(), task.getSourceDatabase(), task.getSourceSchema(), taskId);
            target = connect(task.getTargetConnectorId(), task.getTargetDatabase(), task.getTargetSchema(), taskId);

            // 1. 表结构校验
            if (task.isEnableSchema()) {
                schemaDiff = checkSchema(source, target, sourceTable, targetTable, schemaContent);
            }

            // 2. 行数据校验
            if (task.isEnableRowData()) {
                List<Field> sourceCols = readColumns(source, sourceTable);
                List<Field> targetCols = readColumns(target, targetTable);
                List<Field> pkCols = resolvePkColumns(sourceCols, targetCols, source, sourceTable);
                boolean autoSync = task.isEnableSync();
                long[] rowResult = checkRowData(task, source, target, sourceTable, targetTable,
                        sourceCols, targetCols, pkCols, rowContent, autoSync, taskId);
                sourceTotal = rowResult[0];
                targetTotal = rowResult[1];
                rowDiff = rowResult[2];
                rowFixed = rowResult[3];

                // 3. 反向校验（目标多余行）
                if (task.isEnableReverseScan() && targetTotal <= MAX_REVERSE_SCAN_ROWS) {
                    List<Field> reversePkCols = resolvePkColumns(sourceCols, null, source, sourceTable);
                    boolean autoDelete = task.isEnableReverseSync() || task.isEnableSync();
                    long[] reverse = checkReverse(task, source, target, sourceTable, targetTable,
                            reversePkCols, rowContent, autoDelete, taskId);
                    rowDiff += reverse[0];
                    rowFixed += reverse[1];
                }
            } else {
                sourceTotal = countRows(source, sourceTable);
                targetTotal = countRows(target, targetTable);
            }

            // 4. 索引校验
            if (task.isEnableIndex()) {
                indexDiff = checkIndex(source, target, sourceTable, targetTable, indexContent);
            }
        } catch (Exception e) {
            logger.error("订正校验任务 [{}] 表 [{} -> {}] 校验异常", taskId, sourceTable, targetTable, e);
            error = StringUtil.isBlank(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            closeQuietly(source);
            closeQuietly(target);
            boolean interrupted = isStopping(taskId);
            int status = interrupted ? CommonTaskStatusEnum.READY.getCode() : CommonTaskStatusEnum.DONE.getCode();
            long totalDiff = schemaDiff + rowDiff + indexDiff;
            // 各类型明细行（仅写开启的类型；契约：行 = 表组 × 校验类型）
            saveDetailRow(taskId, tg, CHECK_TYPE_TABLE_SCHEMA, task.isEnableSchema(),
                    0L, 0L, schemaDiff, 0L, schemaContent, error, status);
            saveDetailRow(taskId, tg, CHECK_TYPE_ROW_DATA, task.isEnableRowData(),
                    sourceTotal, targetTotal, rowDiff, rowFixed, rowContent, error, status);
            saveDetailRow(taskId, tg, CHECK_TYPE_INDEX, task.isEnableIndex(),
                    0L, 0L, indexDiff, 0L, indexContent, error, status);
            // 表明细 Meta 汇总（单表组一条）
            meta.setState(status);
            meta.setTotal(new java.util.concurrent.atomic.AtomicLong(sourceTotal));
            meta.setDiff(new java.util.concurrent.atomic.AtomicLong(totalDiff));
            meta.setFixed(new java.util.concurrent.atomic.AtomicLong(rowFixed));
            meta.setFail(new java.util.concurrent.atomic.AtomicLong(StringUtil.isBlank(error) ? 0L : 1L));
            metaProfile.updateMeta(meta);
        }
        return new long[]{sourceTotal, targetTotal, schemaDiff + rowDiff + indexDiff, rowFixed};
    }

    // ------------------------------------------------------------ 结构校验

    /**
     * 表结构比对：字段集合、类型、长度、精度、主键标记。返回差异数。
     * <p>差异 msg 必须以前端正则锚定的格式开头（如 "类型不一致，source: X, target: Y"），
     * key 为字段名，前据此渲染源/目标对比表格。
     */
    private long checkSchema(DatabaseConnectorInstance source, DatabaseConnectorInstance target,
                             String sourceTable, String targetTable,
                             List<Map<String, Object>> content) throws Exception {
        List<Field> srcCols = readColumns(source, sourceTable);
        List<Field> tgtCols = readColumns(target, targetTable);
        Set<String> srcNames = new LinkedHashSet<>();
        for (Field f : srcCols) {
            srcNames.add(f.getName().toLowerCase());
        }
        Set<String> tgtNames = new LinkedHashSet<>();
        for (Field f : tgtCols) {
            tgtNames.add(f.getName().toLowerCase());
        }
        long diff = 0L;
        for (Field f : srcCols) {
            Field t = findColumn(tgtCols, f.getName());
            if (t == null) {
                diff++;
                addItem(content, schemaItem(sourceTable, targetTable,
                        "字段 [" + f.getName() + "] 目标端缺失", f.getName()));
                continue;
            }
            if (!equalsIgnoreCase(f.getTypeName(), t.getTypeName())) {
                diff++;
                addItem(content, schemaItem(sourceTable, targetTable,
                        "类型不一致，source: " + f.getTypeName() + ", target: " + t.getTypeName(),
                        f.getName()));
            }
            if (f.getColumnSize() > 0 && t.getColumnSize() > 0 && f.getColumnSize() != t.getColumnSize()) {
                diff++;
                addItem(content, schemaItem(sourceTable, targetTable,
                        "长度不一致，source: " + f.getColumnSize() + ", target: " + t.getColumnSize(),
                        f.getName()));
            }
            if (f.getRatio() > 0 && t.getRatio() > 0 && f.getRatio() != t.getRatio()) {
                diff++;
                addItem(content, schemaItem(sourceTable, targetTable,
                        "精度不一致，source: " + f.getRatio() + ", target: " + t.getRatio(),
                        f.getName()));
            }
            if (f.isPk() != t.isPk()) {
                diff++;
                addItem(content, schemaItem(sourceTable, targetTable,
                        "主键标记不一致，source: " + (f.isPk() ? "是" : "否") + ", target: " + (t.isPk() ? "是" : "否"),
                        f.getName()));
            }
        }
        for (String n : tgtNames) {
            if (!srcNames.contains(n)) {
                diff++;
                addItem(content, schemaItem(sourceTable, targetTable,
                        "字段 [" + n + "] 源端缺失", n));
            }
        }
        return diff;
    }

    private Map<String, Object> schemaItem(String sourceTable, String targetTable, String msg, String key) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "tableSchema");
        item.put("msg", msg);
        item.put("key", key);
        item.put("sourceTableName", sourceTable);
        item.put("targetTableName", targetTable);
        return item;
    }

    // ------------------------------------------------------------ 行数据校验

    /**
     * 行数据比对（分页读取 + 主键映射）。返回 [sourceTotal, targetTotal, diff, fixed]。
     */
    private long[] checkRowData(ValidateSyncTask task, DatabaseConnectorInstance source, DatabaseConnectorInstance target,
                                String sourceTable, String targetTable,
                                List<Field> sourceCols, List<Field> targetCols, List<Field> pkCols,
                                List<Map<String, Object>> content, boolean autoSync, String taskId) throws Exception {
        long sourceTotal = countRows(source, sourceTable);
        long targetTotal = countRows(target, targetTable);
        long diff = 0L;
        long fixed = 0L;

        if (CollectionUtils.isEmpty(pkCols)) {
            addItem(content, rowDataItem(sourceTable, targetTable, "无主键字段，跳过行数据校验", "", null,
                    Collections.emptyList()));
            return new long[]{sourceTotal, targetTotal, diff, fixed};
        }

        List<String> commonCols = commonColumnNames(sourceCols, targetCols);
        List<String> pkNames = columnNames(pkCols);
        // 对比模式: sample=抽样(按 sampleStep 跳页读, 大表快速健康检查), 抽样模式禁用自动订正
        boolean sampleMode = "sample".equalsIgnoreCase(task.getCompareMode());
        int sampleStep = Math.max(1, task.getSampleStep());
        if (sampleMode) {
            addItem(content, rowDataItem(sourceTable, targetTable,
                    "抽样对比模式(每 " + sampleStep + " 页取 1 页), 差异定位请改用全量模式", "", null,
                    Collections.emptyList()));
            autoSync = false;
        }
        int pageSize = Math.min(Math.max(task.getReadNum(), 100), MAX_PAGE_SIZE);
        int offset = 0;
        while (true) {
            if (isStopping(taskId)) {
                break;
            }
            List<Map<String, Object>> srcPage = pageRows(source, sourceTable, pkNames, commonCols, pageSize, offset);
            if (CollectionUtils.isEmpty(srcPage)) {
                break;
            }
            List<String> keys = new ArrayList<>(srcPage.size());
            for (Map<String, Object> row : srcPage) {
                keys.add(buildKey(row, pkNames));
            }
            Map<String, Map<String, Object>> tgtPage = fetchByKeys(target, targetTable, pkNames, commonCols, keys, target);

            for (Map<String, Object> srcRow : srcPage) {
                String key = buildKey(srcRow, pkNames);
                Map<String, Object> tgtRow = tgtPage.get(key);
                if (tgtRow == null) {
                    diff++;
                    List<Map<String, Object>> details = buildDetails(srcRow, null, commonCols, pkNames);
                    Map<String, Object> item = rowDataItem(sourceTable, targetTable, "目标缺失", key, details, pkNames);
                    item.put("repairSql", buildRepairSql("目标缺失", targetTable, key, details, pkNames, srcRow, null, commonCols));
                    addItem(content, item);
                    if (autoSync && repairInsert(target, targetTable, commonCols, srcRow)) {
                        fixed++;
                        markRepaired(content, key, "目标缺失");
                    }
                    continue;
                }
                List<Map<String, Object>> differs = compareRows(srcRow, tgtRow, commonCols, pkNames);
                if (!differs.isEmpty()) {
                    diff++;
                    Map<String, Object> item = rowDataItem(sourceTable, targetTable, "数据不一致", key, differs, pkNames);
                    item.put("repairSql", buildRepairSql("数据不一致", targetTable, key, differs, pkNames, srcRow, tgtRow, commonCols));
                    addItem(content, item);
                    if (autoSync && repairUpdate(target, targetTable, pkNames, key, differs)) {
                        fixed++;
                        markRepaired(content, key, "数据不一致");
                    }
                }
            }
            offset += srcPage.size() * (sampleMode ? sampleStep : 1);
            if (srcPage.size() < pageSize) {
                break;
            }
        }
        return new long[]{sourceTotal, targetTotal, diff, fixed};
    }

    /**
     * 反向校验：扫描目标库中源端不存在的多余行。返回 [diff, fixed]。
     */
    private long[] checkReverse(ValidateSyncTask task, DatabaseConnectorInstance source, DatabaseConnectorInstance target,
                                String sourceTable, String targetTable, List<Field> pkCols,
                                List<Map<String, Object>> content, boolean autoDelete, String taskId) throws Exception {
        long diff = 0L;
        long fixed = 0L;
        if (CollectionUtils.isEmpty(pkCols)) {
            return new long[]{diff, fixed};
        }
        List<String> pkNames = columnNames(pkCols);
        int pageSize = 1000;
        int offset = 0;
        while (true) {
            if (isStopping(taskId)) {
                break;
            }
            List<Map<String, Object>> tgtPage = pageRows(target, targetTable, pkNames, pkNames, pageSize, offset);
            if (CollectionUtils.isEmpty(tgtPage)) {
                break;
            }
            // 批量存在性检查: 每页一次 IN 查询(替代逐行 EXISTS 的 N+1)
            java.util.Set<String> existsKeys = existsByKeysBatch(source, sourceTable, pkNames, tgtPage);
            for (Map<String, Object> tgtRow : tgtPage) {
                String key = buildKey(tgtRow, pkNames);
                boolean exists = existsKeys.contains(key);
                if (!exists) {
                    diff++;
                    List<Map<String, Object>> details = buildDetails(null, tgtRow, pkNames, pkNames);
                    Map<String, Object> item = rowDataItem(sourceTable, targetTable, "目标多余", key, details, pkNames);
                    item.put("repairSql", buildRepairSql("目标多余", targetTable, key, details, pkNames, null, tgtRow, null));
                    addItem(content, item);
                    if (autoDelete && repairDelete(target, targetTable, pkNames, tgtRow)) {
                        fixed++;
                        markRepaired(content, key, "目标多余");
                    }
                }
            }
            offset += tgtPage.size() * ("sample".equalsIgnoreCase(task.getCompareMode()) ? Math.max(1, task.getSampleStep()) : 1);
            if (tgtPage.size() < pageSize) {
                break;
            }
        }
        return new long[]{diff, fixed};
    }

    private Map<String, Object> rowDataItem(String sourceTable, String targetTable, String msg, String key,
                                            List<Map<String, Object>> details, List<String> pkNames) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "rowData");
        item.put("msg", msg);
        item.put("key", key);
        item.put("sourceTableName", sourceTable);
        item.put("targetTableName", targetTable);
        item.put("details", details == null ? Collections.emptyList() : details);
        // 主键定位信息（订正 UPDATE 需要 WHERE 条件；details 仅含差异字段）
        item.put("pkFields", pkNames == null ? Collections.emptyList() : pkNames);
        return item;
    }

    /**
     * 生成目标端修复 SQL(对标 NineData"差异生成变更脚本"):
     * 目标缺失 -> INSERT(源值); 数据不一致 -> UPDATE(源值覆盖差异列); 目标多余 -> DELETE。
     * 仅供展示/人工执行, 系统内订正走 manualRevise/autoSync。
     */
    private String buildRepairSql(String msg, String targetTable, String key,
                                  List<Map<String, Object>> details, List<String> pkNames,
                                  Map<String, Object> srcRow, Map<String, Object> tgtRow,
                                  List<String> commonCols) {
        try {
            String q = "'";
            java.util.function.Function<Object, String> lit = v -> {
                if (v == null) {
                    return "NULL";
                }
                if (v instanceof byte[]) {
                    // 二进制 -> 0x 十六进制字面量(避免 Java toString 污染修复 SQL)
                    byte[] bytes = (byte[]) v;
                    if (bytes.length == 0) {
                        return "NULL";
                    }
                    StringBuilder hex = new StringBuilder("0x");
                    for (byte b : bytes) {
                        hex.append(String.format("%02X", b));
                    }
                    return hex.toString();
                }
                if (v instanceof Number || v instanceof Boolean) {
                    return String.valueOf(v);
                }
                String t = String.valueOf(v);
                if (DATETIME_PATTERN.matcher(t).matches() || DATE_PATTERN.matcher(t).matches()
                        || TIME_PATTERN.matcher(t).matches()) {
                    return q + t + q;
                }
                return q + t.replace(q, q + q) + q;
            };
            StringBuilder where = new StringBuilder();
            if (tgtRow != null) {
                for (String pk : pkNames) {
                    if (where.length() > 0) {
                        where.append(" AND ");
                    }
                    where.append(pk).append(" = ").append(lit.apply(tgtRow.get(pk)));
                }
            } else if (srcRow != null) {
                for (String pk : pkNames) {
                    if (where.length() > 0) {
                        where.append(" AND ");
                    }
                    where.append(pk).append(" = ").append(lit.apply(srcRow.get(pk)));
                }
            }
            if (where.length() == 0) {
                where.append(key == null ? "NULL" : key);
            }
            if (msg.contains("目标多余")) {
                return "DELETE FROM " + targetTable + " WHERE " + where + ";";
            }
            if (msg.contains("目标缺失") && srcRow != null && commonCols != null) {
                StringBuilder cols = new StringBuilder();
                StringBuilder vals = new StringBuilder();
                for (String col : commonCols) {
                    if (cols.length() > 0) {
                        cols.append(", ");
                        vals.append(", ");
                    }
                    cols.append(col);
                    vals.append(lit.apply(srcRow.get(col)));
                }
                return "INSERT INTO " + targetTable + " (" + cols + ") VALUES (" + vals + ");";
            }
            if (msg.contains("数据不一致") && srcRow != null && details != null) {
                StringBuilder sets = new StringBuilder();
                for (Map<String, Object> d : details) {
                    Object field = d.get("field");
                    if (field == null) {
                        continue;
                    }
                    if (sets.length() > 0) {
                        sets.append(", ");
                    }
                    sets.append(field).append(" = ").append(lit.apply(d.get("source")));
                }
                if (sets.length() == 0) {
                    return "";
                }
                return "UPDATE " + targetTable + " SET " + sets + " WHERE " + where + ";";
            }
        } catch (Exception e) {
            logger.debug("buildRepairSql failed: {}", e.getMessage());
        }
        return "";
    }

    private List<Map<String, Object>> compareRows(Map<String, Object> srcRow, Map<String, Object> tgtRow,
                                                  List<String> commonCols, List<String> pkNames) {
        List<Map<String, Object>> differs = new ArrayList<>();
        for (String col : commonCols) {
            Object s = normalize(srcRow.get(col));
            Object t = normalize(tgtRow.get(col));
            if (!valueEquals(s, t)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("field", col);
                d.put("source", s);
                d.put("target", t);
                d.put("pk", pkNames.contains(col));
                differs.add(d);
            }
        }
        return differs;
    }

    private List<Map<String, Object>> buildDetails(Map<String, Object> srcRow, Map<String, Object> tgtRow,
                                                   List<String> cols, List<String> pkNames) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (String col : cols) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("field", col);
            d.put("source", srcRow == null ? null : normalize(srcRow.get(col)));
            d.put("target", tgtRow == null ? null : normalize(tgtRow.get(col)));
            d.put("pk", pkNames.contains(col));
            details.add(d);
        }
        return details;
    }

    // ------------------------------------------------------------ 索引校验

    /**
     * 索引比对（JDBC DatabaseMetaData 标准接口）。返回差异数。
     */
    private long checkIndex(DatabaseConnectorInstance source, DatabaseConnectorInstance target,
                            String sourceTable, String targetTable,
                            List<Map<String, Object>> content) throws Exception {
        Map<String, String> srcIdx = readIndexes(source, sourceTable);
        Map<String, String> tgtIdx = readIndexes(target, targetTable);
        long diff = 0L;
        for (Map.Entry<String, String> e : srcIdx.entrySet()) {
            String name = e.getKey();
            String columns = e.getValue();
            String tgtColumns = tgtIdx.get(name);
            if (tgtColumns == null) {
                diff++;
                addItem(content, indexItem(sourceTable, targetTable, "索引 [" + name + "] 目标端缺失", name));
            } else if (!columns.equalsIgnoreCase(tgtColumns)) {
                diff++;
                addItem(content, indexItem(sourceTable, targetTable,
                        "索引 [" + name + "] 字段不一致，source: " + columns + ", target: " + tgtColumns, name));
            }
        }
        for (String name : tgtIdx.keySet()) {
            if (!srcIdx.containsKey(name)) {
                diff++;
                addItem(content, indexItem(sourceTable, targetTable, "索引 [" + name + "] 源端缺失", name));
            }
        }
        return diff;
    }

    private Map<String, Object> indexItem(String sourceTable, String targetTable, String msg, String key) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "index");
        item.put("msg", msg);
        item.put("key", key);
        item.put("sourceTableName", sourceTable);
        item.put("targetTableName", targetTable);
        return item;
    }

    private Map<String, String> readIndexes(DatabaseConnectorInstance db, String table) {
        return db.execute(t -> t.execute((ConnectionCallback<Map<String, String>>) con -> {
            Map<String, String> idx = new LinkedHashMap<>();
            DatabaseMetaData md = con.getMetaData();
            ResultSet rs = null;
            try {
                rs = md.getIndexInfo(con.getCatalog(), con.getSchema(), table, false, false);
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    String column = rs.getString("COLUMN_NAME");
                    if (name == null || column == null) {
                        continue;
                    }
                    // 过滤主键索引（MySQL=PRIMARY，PG=<table>_pkey 等）：主键一致性已由行数据校验保障
                    if ("PRIMARY".equalsIgnoreCase(name) || name.toLowerCase().endsWith("_pkey")
                            || name.toLowerCase().startsWith("sqlite_autoindex")) {
                        continue;
                    }
                    idx.merge(name, column, (a, b) -> a + "," + b);
                }
            } finally {
                closeResultSet(rs);
            }
            return idx;
        }));
    }

    // ------------------------------------------------------------ 订正（enableSync / 手动订正共用）

    /**
     * 执行差异订正：遍历 content 中行级差异写回目标库，单次最多处理 {@link #MAX_REPAIR_ITEMS} 条。
     *
     * @return 成功订正条数（content 就地标记订正结果）
     */
    public int repairContent(String taskId, Object contentObj) {
        List<Object> items = extractItems(contentObj);
        if (items.isEmpty()) {
            return 0;
        }
        ValidateSyncTask task = taskProfile.getTask(taskId, ValidateSyncTask.class);
        Assert.notNull(task, "任务不存在");

        Map<String, DatabaseConnectorInstance> targets = new HashMap<>();
        int repaired = 0;
        int processed = 0;
        try {
            for (Object o : items) {
                if (processed >= MAX_REPAIR_ITEMS) {
                    break;
                }
                Map<String, Object> item = asMap(o);
                if (item == null || !"rowData".equals(item.get("type"))) {
                    continue;
                }
                String msg = str(item.get("msg"));
                if (msg == null || msg.contains("(已订正)") || msg.contains("(订正失败")) {
                    continue;
                }
                String targetTable = str(item.get("targetTableName"));
                List<Map<String, Object>> details = asMapList(item.get("details"));
                if (StringUtil.isBlank(targetTable) || details == null || details.isEmpty()) {
                    continue;
                }
                boolean handled = false;
                boolean ok = false;
                try {
                    DatabaseConnectorInstance target = targets.computeIfAbsent(targetTable,
                            k -> connect(task.getTargetConnectorId(), task.getTargetDatabase(), task.getTargetSchema(), taskId));
                    if (msg.contains("目标缺失")) {
                        ok = insertFromDetails(target, targetTable, details);
                        handled = true;
                    } else if (msg.contains("数据不一致")) {
                        ok = updateFromDetails(target, targetTable, item, details);
                        handled = true;
                    } else if (msg.contains("目标多余")) {
                        ok = deleteFromDetails(target, targetTable, details);
                        handled = true;
                    }
                } catch (Exception e) {
                    logger.warn("订正校验任务 [{}] 差异订正失败: {}", taskId, e.getMessage());
                    item.put("msg", msg + "(订正失败:" + e.getMessage() + ")");
                    handled = true;
                }
                if (handled) {
                    processed++;
                    if (ok) {
                        repaired++;
                        item.put("msg", msg + "(已订正)");
                    } else if (!msg.contains("(订正失败")) {
                        item.put("msg", msg + "(订正失败:影响行数为0)");
                    }
                }
            }
        } finally {
            for (DatabaseConnectorInstance instance : targets.values()) {
                closeQuietly(instance);
            }
        }
        return repaired;
    }

    private boolean insertFromDetails(DatabaseConnectorInstance target, String table,
                                      List<Map<String, Object>> details) {
        List<String> cols = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> d : details) {
            cols.add(str(d.get("field")));
            values.add(castForBind(d.get("source")));
        }
        if (cols.isEmpty()) {
            return false;
        }
        String sql = "INSERT INTO " + quote(target, table) + " (" + joinQuoted(target, cols) + ") VALUES ("
                + placeholders(cols.size()) + ")";
        return executeUpdate(target, sql, values) > 0;
    }

    /**
     * 订正"数据不一致"：SET 差异字段为源值，WHERE 用 item.pkFields + item.key 定位行
     * （details 仅含差异字段，不含主键）。
     */
    private boolean updateFromDetails(DatabaseConnectorInstance target, String table, Map<String, Object> item,
                                      List<Map<String, Object>> details) {
        List<String> setCols = new ArrayList<>();
        List<Object> setVals = new ArrayList<>();
        List<String> pkCols = new ArrayList<>();
        List<Object> pkVals = new ArrayList<>();
        for (Map<String, Object> d : details) {
            if (Boolean.parseBoolean(String.valueOf(d.get("pk")))) {
                continue;
            }
            setCols.add(str(d.get("field")));
            setVals.add(castForBind(d.get("source")));
        }
        if (setCols.isEmpty() || !resolvePkFromItem(item, pkCols, pkVals)) {
            return false;
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(quote(target, table)).append(" SET ");
        for (int i = 0; i < setCols.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(quote(target, setCols.get(i))).append(" = ?");
        }
        sql.append(" WHERE ");
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(quote(target, pkCols.get(i))).append(" = ?");
        }
        List<Object> args = new ArrayList<>(setVals);
        args.addAll(pkVals);
        return executeUpdate(target, sql.toString(), args) > 0;
    }

    /**
     * 从 content 项解析主键定位（pkFields + 组合 key，按 "|" 分隔）。
     */
    private boolean resolvePkFromItem(Map<String, Object> item, List<String> pkCols, List<Object> pkVals) {
        Object fields = item.get("pkFields");
        String key = str(item.get("key"));
        if (!(fields instanceof List) || ((List) fields).isEmpty() || StringUtil.isBlank(key)) {
            return false;
        }
        String[] vals = key.split("\\|", -1);
        int i = 0;
        for (Object o : (List) fields) {
            pkCols.add(str(o));
            pkVals.add(i < vals.length ? castKey(vals[i]) : vals[i]);
            i++;
        }
        return true;
    }

    private boolean deleteFromDetails(DatabaseConnectorInstance target, String table,
                                      List<Map<String, Object>> details) {
        List<String> pkCols = new ArrayList<>();
        List<Object> pkVals = new ArrayList<>();
        for (Map<String, Object> d : details) {
            Object source = d.get("source");
            pkCols.add(str(d.get("field")));
            pkVals.add(castForBind(source == null ? d.get("target") : source));
        }
        if (pkCols.isEmpty()) {
            return false;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(quote(target, table)).append(" WHERE ");
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(quote(target, pkCols.get(i))).append(" = ?");
        }
        return executeUpdate(target, sql.toString(), pkVals) > 0;
    }

    private boolean repairInsert(DatabaseConnectorInstance target, String table, List<String> cols,
                                 Map<String, Object> srcRow) {
        List<Object> values = new ArrayList<>(cols.size());
        for (String col : cols) {
            values.add(castForBind(srcRow.get(col)));
        }
        String sql = "INSERT INTO " + quote(target, table) + " (" + joinQuoted(target, cols) + ") VALUES ("
                + placeholders(cols.size()) + ")";
        return executeUpdate(target, sql, values) > 0;
    }

    private boolean repairUpdate(DatabaseConnectorInstance target, String table, List<String> pkNames, String key,
                                 List<Map<String, Object>> differs) {
        List<String> setCols = new ArrayList<>();
        List<Object> setVals = new ArrayList<>();
        for (Map<String, Object> d : differs) {
            String col = str(d.get("field"));
            if (pkNames.contains(col)) {
                continue;
            }
            setCols.add(col);
            setVals.add(castForBind(d.get("source")));
        }
        if (setCols.isEmpty()) {
            // 仅主键值本身不一致时无法安全更新
            return false;
        }
        List<String> pkCols = new ArrayList<>();
        List<Object> pkVals = new ArrayList<>();
        Map<String, Object> holder = new HashMap<>();
        holder.put("pkFields", pkNames);
        holder.put("key", key);
        if (!resolvePkFromItem(holder, pkCols, pkVals)) {
            return false;
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(quote(target, table)).append(" SET ");
        for (int i = 0; i < setCols.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(quote(target, setCols.get(i))).append(" = ?");
        }
        sql.append(" WHERE ");
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(quote(target, pkCols.get(i))).append(" = ?");
        }
        List<Object> args = new ArrayList<>(setVals);
        args.addAll(pkVals);
        return executeUpdate(target, sql.toString(), args) > 0;
    }

    private boolean repairDelete(DatabaseConnectorInstance target, String table, List<String> pkNames,
                                 Map<String, Object> row) {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(quote(target, table)).append(" WHERE ");
        List<Object> args = new ArrayList<>(pkNames.size());
        for (int i = 0; i < pkNames.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(quote(target, pkNames.get(i))).append(" = ?");
            args.add(castForBind(row.get(pkNames.get(i))));
        }
        return executeUpdate(target, sql.toString(), args) > 0;
    }

    // ------------------------------------------------------------ 连接与查询

    private DatabaseConnectorInstance connect(String connectorId, String database, String schema, String taskId) {
        Assert.hasText(connectorId, "连接器ID不能为空");
        Connector connector = connectorProfile.getConnector(connectorId);
        Assert.notNull(connector, "连接器不存在: " + connectorId);
        ConnectorConfig config = connector.getConfig();
        Assert.notNull(config, "连接器配置不存在: " + connectorId);
        String instanceId = "vs-" + taskId + "-" + connectorId;
        ConnectorInstance instance = connectorFactory.connect(instanceId, config, database, schema);
        if (!(instance instanceof DatabaseConnectorInstance)) {
            throw new IllegalArgumentException("连接器类型不支持数据校验: " + config.getConnectorType());
        }
        return (DatabaseConnectorInstance) instance;
    }

    private long countRows(DatabaseConnectorInstance db, String table) {
        Object cnt = db.execute(t -> t.query("SELECT COUNT(1) AS cnt FROM " + quote(db, table),
                rs -> rs.next() ? rs.getLong(1) : 0L));
        long n = cnt instanceof Number ? ((Number) cnt).longValue() : 0L;
        logger.info("countRows [{}] = {}", table, n);
        return n;
    }

    private List<Map<String, Object>> pageRows(DatabaseConnectorInstance db, String table, List<String> orderCols,
                                               List<String> cols, int pageSize, int offset) {
        String select = cols.isEmpty() ? "*" : joinQuoted(db, cols);
        StringBuilder sql = new StringBuilder("SELECT ").append(select).append(" FROM ").append(quote(db, table));
        if (!orderCols.isEmpty()) {
            sql.append(" ORDER BY ").append(joinQuoted(db, orderCols));
        }
        sql.append(" LIMIT ? OFFSET ?");
        return db.execute(t -> t.query(sql.toString(), rs -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    row.put(md.getColumnLabel(i), normalize(rs.getObject(i)));
                }
                rows.add(row);
            }
            return rows;
        }, pageSize, offset));
    }

    private Map<String, Map<String, Object>> fetchByKeys(DatabaseConnectorInstance db, String table,
                                                         List<String> pkNames, List<String> cols, List<String> keys,
                                                         DatabaseConnectorInstance dialectDb) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (keys.isEmpty() || pkNames.isEmpty()) {
            return result;
        }
        String select = cols.isEmpty() ? "*" : joinQuoted(db, cols);
        int batch = 300;
        for (int from = 0; from < keys.size(); from += batch) {
            List<String> part = keys.subList(from, Math.min(from + batch, keys.size()));
            StringBuilder sql = new StringBuilder("SELECT ").append(select).append(" FROM ").append(quote(db, table))
                    .append(" WHERE ");
            for (int i = 0; i < pkNames.size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                sql.append(quote(db, pkNames.get(i))).append(" IN (");
                for (int j = 0; j < part.size(); j++) {
                    if (j > 0) {
                        sql.append(",");
                    }
                    sql.append("?");
                }
                sql.append(")");
            }
            // 组合主键无法用扁平 IN 展开，退化为逐 key 查询
            if (pkNames.size() > 1) {
                for (String key : part) {
                    Map<String, Object> row = fetchOneByKey(db, table, pkNames, cols, key);
                    if (row != null) {
                        result.put(key, row);
                    }
                }
                continue;
            }
            List<Object> args = new ArrayList<>(part.size());
            // 键为拼接字符串，需还原为数值类型，避免 PG 等强类型库 integer = varchar 报错
            for (String k : part) {
                args.add(castKey(k));
            }
            List<Map<String, Object>> rows = db.execute(t -> t.query(sql.toString(), rs -> {
                List<Map<String, Object>> list = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(md.getColumnLabel(i), normalize(rs.getObject(i)));
                    }
                    list.add(row);
                }
                return list;
            }, args.toArray()));
            for (Map<String, Object> row : rows) {
                result.put(buildKey(row, pkNames), row);
            }
        }
        return result;
    }

    private Map<String, Object> fetchOneByKey(DatabaseConnectorInstance db, String table, List<String> pkNames,
                                              List<String> cols, String key) {
        String select = cols.isEmpty() ? "*" : joinQuoted(db, cols);
        StringBuilder sql = new StringBuilder("SELECT ").append(select).append(" FROM ").append(quote(db, table))
                .append(" WHERE ");
        String[] vals = key.split("\\|", -1);
        List<Object> args = new ArrayList<>(pkNames.size());
        for (int i = 0; i < pkNames.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(quote(db, pkNames.get(i))).append(" = ?");
            args.add(i < vals.length ? castKey(vals[i]) : vals[i]);
        }
        return db.execute(t -> t.query(sql.toString(), rs -> {
            if (!rs.next()) {
                return null;
            }
            ResultSetMetaData md = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                row.put(md.getColumnLabel(i), normalize(rs.getObject(i)));
            }
            return row;
        }, args.toArray()));
    }

    private boolean existsByKey(DatabaseConnectorInstance db, String table, List<String> pkNames,
                                Map<String, Object> row) {
        StringBuilder sql = new StringBuilder("SELECT 1 FROM ").append(quote(db, table)).append(" WHERE ");
        List<Object> args = new ArrayList<>(pkNames.size());
        for (int i = 0; i < pkNames.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(quote(db, pkNames.get(i))).append(" = ?");
            Object kv = row.get(pkNames.get(i));
            if (kv instanceof String) {
                kv = castKey((String) kv);
            }
            args.add(kv);
        }
        sql.append(" LIMIT 1");
        Object found = db.execute(t -> t.query(sql.toString(), rs -> rs.next() ? Boolean.TRUE : Boolean.FALSE,
                args.toArray()));
        return Boolean.TRUE.equals(found);
    }

    /**
     * 批量存在性检查: 对一页目标行, 用一次 IN 查询取得源端已存在的主键集合。
     * 参数按批次拆分(单批上限 500), 键值统一走 castKey 类型对齐。
     */
    private java.util.Set<String> existsByKeysBatch(DatabaseConnectorInstance db, String table,
                                                    List<String> pkNames, List<Map<String, Object>> rows) {
        java.util.Set<String> exists = new java.util.HashSet<>();
        if (CollectionUtils.isEmpty(rows) || CollectionUtils.isEmpty(pkNames)) {
            return exists;
        }
        int batchSize = 500;
        for (int from = 0; from < rows.size(); from += batchSize) {
            List<Map<String, Object>> batch = rows.subList(from, Math.min(from + batchSize, rows.size()));
            StringBuilder sql = new StringBuilder("SELECT ").append(quote(db, pkNames.get(0)));
            for (int i = 1; i < pkNames.size(); i++) {
                sql.append(", ").append(quote(db, pkNames.get(i)));
            }
            sql.append(" FROM ").append(quote(db, table)).append(" WHERE ");
            if (pkNames.size() == 1) {
                sql.append(quote(db, pkNames.get(0))).append(" IN (");
                for (int i = 0; i < batch.size(); i++) {
                    if (i > 0) {
                        sql.append(",");
                    }
                    sql.append("?");
                }
                sql.append(")");
            } else {
                // 联合主键: (pk1,pk2) IN ((?,?),(?,?)...)
                sql.append("(");
                for (int i = 0; i < pkNames.size(); i++) {
                    if (i > 0) {
                        sql.append(",");
                    }
                    sql.append(quote(db, pkNames.get(i)));
                }
                sql.append(") IN (");
                for (int i = 0; i < batch.size(); i++) {
                    if (i > 0) {
                        sql.append(",");
                    }
                    sql.append("(");
                    for (int j = 0; j < pkNames.size(); j++) {
                        if (j > 0) {
                            sql.append(",");
                        }
                        sql.append("?");
                    }
                    sql.append(")");
                }
                sql.append(")");
            }
            List<Object> args = new ArrayList<>(batch.size() * pkNames.size());
            for (Map<String, Object> row : batch) {
                for (String pk : pkNames) {
                    Object kv = row.get(pk);
                    if (kv instanceof String) {
                        kv = castKey((String) kv);
                    }
                    args.add(kv);
                }
            }
            List<Map<String, Object>> found = db.execute(t -> t.queryForList(sql.toString(), args.toArray()));
            if (!CollectionUtils.isEmpty(found)) {
                for (Map<String, Object> row : found) {
                    exists.add(buildKey(row, pkNames));
                }
            }
        }
        return exists;
    }

    private int executeUpdate(DatabaseConnectorInstance db, String sql, List<Object> args) {
        Integer n = db.execute(t -> t.update(sql, args.toArray()));
        return n == null ? 0 : n;
    }

    private List<Field> readColumns(DatabaseConnectorInstance db, String table) {
        return db.execute(t -> t.query("SELECT * FROM " + quote(db, table) + " WHERE 1=0", (ResultSetExtractor<List<Field>>) rs -> {
            ResultSetMetaData md = rs.getMetaData();
            List<Field> cols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                Field f = new Field(md.getColumnLabel(i), md.getColumnTypeName(i), md.getColumnType(i),
                        false, md.getPrecision(i), md.getScale(i));
                cols.add(f);
            }
            return cols;
        }));
    }

    private List<Field> readPrimaryKeys(DatabaseConnectorInstance db, String table) {
        return db.execute(t -> t.execute((ConnectionCallback<List<Field>>) con -> {
            Map<String, Field> all = new HashMap<>();
            List<Field> cols = readColumnsDb(con, table, all);
            List<Field> pks = new ArrayList<>();
            DatabaseMetaData md = con.getMetaData();
            ResultSet keys = null;
            try {
                keys = md.getPrimaryKeys(con.getCatalog(), con.getSchema(), table);
                while (keys.next()) {
                    String name = keys.getString("COLUMN_NAME");
                    Field f = all.get(name.toUpperCase());
                    if (f != null) {
                        pks.add(f);
                    }
                }
            } finally {
                closeResultSet(keys);
            }
            if (pks.isEmpty() && !cols.isEmpty()) {
                pks.add(cols.get(0));
            }
            return pks;
        }));
    }

    private List<Field> readColumnsDb(Connection con, String table, Map<String, Field> all) throws java.sql.SQLException {
        java.sql.Statement st = null;
        ResultSet rs = null;
        try {
            st = con.createStatement();
            rs = st.executeQuery("SELECT * FROM " + table + " WHERE 1=0");
            ResultSetMetaData md = rs.getMetaData();
            List<Field> cols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                Field f = new Field(md.getColumnLabel(i), md.getColumnTypeName(i), md.getColumnType(i),
                        false, md.getPrecision(i), md.getScale(i));
                all.put(f.getName().toUpperCase(), f);
                cols.add(f);
            }
            return cols;
        } finally {
            closeResultSet(rs);
            if (st != null) {
                st.close();
            }
        }
    }

    // ------------------------------------------------------------ 明细与 Meta 持久化

    /**
     * 按类型写明细行；行缺失（未预建）时自建兜底。仅在该类型开启时执行。
     */
    private void saveDetailRow(String taskId, TableGroup tg, String type, boolean enabled,
                               long sourceTotal, long targetTotal, long diffTotal, long fixedTotal,
                               List<Map<String, Object>> content, String error, int status) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(ConfigConstant.TASK_SOURCE_TOTAL, sourceTotal);
            data.put(ConfigConstant.TASK_TARGET_TOTAL, targetTotal);
            data.put(ConfigConstant.TASK_DIFF_TOTAL, diffTotal);
            data.put(ConfigConstant.TASK_FIXED_TOTAL, fixedTotal);
            data.put(ConfigConstant.TASK_CONTENT, content);
            data.put(ConfigConstant.TASK_STATUS, status);

            Map<String, Object> row = findDetailRow(taskId, tg.getId(), type);
            if (row == null) {
                row = new HashMap<>();
                row.put(ConfigConstant.CONFIG_MODEL_ID, UUIDUtil.getUUID());
                row.put(ConfigConstant.DATA_TABLE_GROUP_ID, tg.getId());
                row.put(ConfigConstant.CONFIG_MODEL_TYPE, type);
                row.put(ConfigConstant.DETAIL_TARGET_TABLE, tableName(tg.getTargetTable()));
                row.put(ConfigConstant.CONFIG_MODEL_CREATE_TIME, System.currentTimeMillis());
            }
            row.put(ConfigConstant.DETAIL_IS_SUCCESS, diffTotal <= fixedTotal ? 1 : 0);
            row.put(ConfigConstant.DATA_ERROR, error == null ? StringUtil.EMPTY : error);
            row.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, System.currentTimeMillis());
            row.put(ConfigConstant.BINLOG_DATA, JsonUtil.objToJson(data));
            storageService.editBatch(StorageEnum.TASK_DETAIL, taskId, Collections.singletonList(row));
        } catch (Exception e) {
            logger.error("订正校验任务 [{}] 写入明细结果失败: tableGroupId={}, type={}", taskId, tg.getId(), type, e);
        }
    }

    private Map<String, Object> findDetailRow(String taskId, String tableGroupId, String type) {
        checkTaskId(taskId);
        String table = "DBDataSync_task_detail_" + taskId;
        List<Map<String, Object>> rows = storageService.queryList(
                org.dbdatasync.sdk.storage.SqlQuery.of(
                        "SELECT ID AS ID, CREATE_TIME AS CREATETIME, UPDATE_TIME AS UPDATETIME, TABLE_GROUP_ID AS TABLEGROUPID, "
                                + "TYPE AS TYPE, TARGET_TABLE AS TARGETTABLE, IS_SUCCESS AS ISSUCCESS, ERROR AS ERROR, DATA AS DATA "
                                + "FROM " + table + " WHERE TABLE_GROUP_ID = ? AND TYPE = ?", tableGroupId, type));
        if (CollectionUtils.isEmpty(rows)) {
            return null;
        }
        Map<String, Object> raw = rows.get(0);
        // 转回 labelName 驼峰 key，供 editBatch 使用
        Map<String, Object> row = new HashMap<>();
        row.put(ConfigConstant.CONFIG_MODEL_ID, raw.get("ID"));
        row.put(ConfigConstant.CONFIG_MODEL_CREATE_TIME, raw.get("CREATETIME"));
        row.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, raw.get("UPDATETIME"));
        row.put(ConfigConstant.DATA_TABLE_GROUP_ID, raw.get("TABLEGROUPID"));
        row.put(ConfigConstant.CONFIG_MODEL_TYPE, raw.get("TYPE"));
        row.put(ConfigConstant.DETAIL_TARGET_TABLE, raw.get("TARGETTABLE"));
        row.put(ConfigConstant.DETAIL_IS_SUCCESS, raw.get("ISSUCCESS"));
        row.put(ConfigConstant.DATA_ERROR, raw.get("ERROR"));
        row.put(ConfigConstant.BINLOG_DATA, raw.get("DATA"));
        return row;
    }

    private Meta ensureMeta(String refId, boolean detailLevel) {
        TaskLevelEnum level = detailLevel ? TaskLevelEnum.TASK_DETAIL : TaskLevelEnum.TASK;
        Meta meta = metaProfile.getMetaByTaskId(refId, level);
        if (meta == null) {
            meta = new Meta();
            meta.setId(UUIDUtil.getUUID());
            meta.setTaskId(refId);
            meta.setIsTaskDetail(detailLevel ? 1 : 0);
            metaProfile.addMeta(meta);
        }
        return meta;
    }

    private List<TableGroup> loadTableGroups(String taskId) {
        List<String> ids = tableGroupProfile.listTableGroupIds(taskId);
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        List<TableGroup> groups = new ArrayList<>(ids.size());
        for (String id : ids) {
            TableGroup tg = tableGroupProfile.getTableGroup(id);
            if (tg != null) {
                groups.add(tg);
            }
        }
        return groups;
    }

    // ------------------------------------------------------------ 工具方法

    private List<Object> extractItems(Object contentObj) {
        if (contentObj == null) {
            return Collections.emptyList();
        }
        if (contentObj instanceof List) {
            return (List<Object>) contentObj;
        }
        if (contentObj instanceof Map) {
            Object data = ((Map<String, Object>) contentObj).get("data");
            if (data instanceof List) {
                return (List<Object>) data;
            }
            return Collections.emptyList();
        }
        if (contentObj instanceof String) {
            String s = ((String) contentObj).trim();
            if (s.isEmpty()) {
                return Collections.emptyList();
            }
            try {
                List list = JsonUtil.parseList(s);
                return list == null ? Collections.emptyList() : list;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    private void addItem(List<Map<String, Object>> content, Map<String, Object> item) {
        if (content.size() < MAX_CONTENT_ITEMS) {
            content.add(item);
        } else if (content.size() == MAX_CONTENT_ITEMS) {
            Map<String, Object> tip = new LinkedHashMap<>();
            tip.put("type", "tip");
            tip.put("msg", "差异数过多，仅展示前 " + MAX_CONTENT_ITEMS + " 条");
            content.add(tip);
        }
    }

    private void markRepaired(List<Map<String, Object>> content, String key, String msg) {
        for (Map<String, Object> item : content) {
            if (key != null && key.equals(str(item.get("key"))) && msg.equals(str(item.get("msg")))) {
                item.put("msg", msg + "(已订正)");
                return;
            }
        }
    }

    private String tableName(Table table) {
        return table != null && StringUtil.isNotBlank(table.getName()) ? table.getName() : StringUtil.EMPTY;
    }

    private List<String> commonColumnNames(List<Field> sourceCols, List<Field> targetCols) {
        List<String> common = new ArrayList<>();
        if (CollectionUtils.isEmpty(sourceCols)) {
            return common;
        }
        Set<String> targetNames = new HashSet<>();
        if (!CollectionUtils.isEmpty(targetCols)) {
            for (Field f : targetCols) {
                targetNames.add(f.getName().toLowerCase());
            }
        }
        for (Field f : sourceCols) {
            if (targetNames.isEmpty() || targetNames.contains(f.getName().toLowerCase())) {
                common.add(f.getName());
            }
        }
        return common;
    }

    private List<String> columnNames(List<Field> cols) {
        List<String> names = new ArrayList<>();
        if (CollectionUtils.isEmpty(cols)) {
            return names;
        }
        for (Field f : cols) {
            names.add(f.getName());
        }
        return names;
    }

    /**
     * 解析主键字段：优先源端主键元数据；无主键时退化为第一列并提示退化行为。
     */
    private List<Field> resolvePkColumns(List<Field> sourceCols, List<Field> targetCols,
                                         DatabaseConnectorInstance source, String sourceTable) {
        try {
            List<Field> pks = readPrimaryKeys(source, sourceTable);
            if (!CollectionUtils.isEmpty(pks)) {
                return pks;
            }
        } catch (Exception e) {
            logger.warn("读取源表 [{}] 主键失败: {}", sourceTable, e.getMessage());
        }
        return Collections.emptyList();
    }

    private String buildKey(Map<String, Object> row, List<String> pkNames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkNames.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            Object v = row.get(pkNames.get(i));
            sb.append(v == null ? "" : String.valueOf(normalize(v)));
        }
        return sb.toString();
    }

    private Object castKey(String val) {
        if (val == null) {
            return null;
        }
        if (val.matches("-?\\d+")) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException e) {
                return val;
            }
        }
        return val;
    }

    private Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        // 统一日期时间表示：跨库（MySQL LocalDateTime / PG Timestamp 等）字符串一致才能正确比对
        if (v instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) v).toLocalDateTime().toString();
        }
        if (v instanceof java.sql.Date) {
            return ((java.sql.Date) v).toLocalDate().toString();
        }
        if (v instanceof java.sql.Time) {
            return ((java.sql.Time) v).toLocalTime().toString();
        }
        if (v instanceof java.time.LocalDateTime || v instanceof java.time.LocalDate
                || v instanceof java.time.LocalTime || v instanceof java.time.ZonedDateTime
                || v instanceof java.time.OffsetDateTime) {
            return String.valueOf(v);
        }
        if (v instanceof java.util.Date && !(v instanceof java.sql.Date)) {
            return new java.sql.Timestamp(((java.util.Date) v).getTime()).toLocalDateTime().toString();
        }
        return v;
    }

    /**
     * 订正绑定值还原：比对时日期时间已统一为 ISO 字符串，写回目标库前转回 JDBC 类型，
     * 避免 PG 等强类型库 varchar 无法隐式转 timestamp。
     */
    private Object castForBind(Object v) {
        if (v instanceof String) {
            String s = (String) v;
            if (s.length() >= 19 && s.charAt(10) == 'T' && DATETIME_PATTERN.matcher(s).matches()) {
                return Timestamp.valueOf(s.replace('T', ' '));
            }
            if (DATE_PATTERN.matcher(s).matches()) {
                return java.sql.Date.valueOf(s);
            }
            if (TIME_PATTERN.matcher(s).matches()) {
                return java.sql.Time.valueOf(s);
            }
        }
        return v;
    }

    private boolean valueEquals(Object s, Object t) {
        if (s == null && t == null) {
            return true;
        }
        if (s == null || t == null) {
            return false;
        }
        if (s.equals(t)) {
            return true;
        }
        String a = String.valueOf(s);
        String b = String.valueOf(t);
        if (a.equals(b)) {
            return true;
        }
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equalsIgnoreCase(b) || normalizedType(a).equals(normalizedType(b));
    }

    /** 宽松类型归一：INT/INTEGER、VARCHAR/CHARACTER VARYING 等视作等价 */
    private String normalizedType(String typeName) {
        String t = typeName == null ? "" : typeName.toUpperCase();
        switch (t) {
            case "INT":
            case "INTEGER":
                return "INTEGER";
            case "VARCHAR":
            case "CHARACTER VARYING":
                return "VARCHAR";
            case "BIGINT":
            case "BIGINT UNSIGNED":
                return "BIGINT";
            case "DATETIME":
            case "TIMESTAMP":
                return "TIMESTAMP";
            default:
                return t;
        }
    }

    private Field findColumn(List<Field> cols, String name) {
        if (CollectionUtils.isEmpty(cols)) {
            return null;
        }
        for (Field f : cols) {
            if (f.getName().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    private String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }

    private String quote(DatabaseConnectorInstance db, String name) {
        String type = db.getConfig() != null ? db.getConfig().getConnectorType() : "";
        if (type != null && type.toUpperCase().contains("MYSQL")) {
            return "`" + name + "`";
        }
        return "\"" + name + "\"";
    }

    private String joinQuoted(DatabaseConnectorInstance db, List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(db, names.get(i)));
        }
        return sb.toString();
    }

    private void checkTaskId(String taskId) {
        Assert.isTrue(taskId != null && taskId.matches("[A-Za-z0-9_-]+"), "非法任务ID");
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Map<String, Object> asMap(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        if (o instanceof String) {
            try {
                Object parsed = JsonUtil.parseMap((String) o);
                return parsed instanceof Map ? (Map<String, Object>) parsed : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private List<Map<String, Object>> asMapList(Object o) {
        if (!(o instanceof List)) {
            return null;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object item : (List) o) {
            Map<String, Object> m = asMap(item);
            if (m != null) {
                list.add(m);
            }
        }
        return list;
    }

    private void closeResultSet(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    private void closeQuietly(DatabaseConnectorInstance instance) {
        if (instance == null) {
            return;
        }
        try {
            instance.close();
        } catch (Exception e) {
            logger.debug("关闭连接实例失败: {}", e.getMessage());
        }
    }
}
