package org.dbdatasync.biz.precheck;

import org.dbdatasync.biz.BizException;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.ParserComponent;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.model.Connector;
import org.dbdatasync.parser.model.FieldMapping;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.util.ConnectorServiceContextUtil;
import org.dbdatasync.sdk.config.ListenerConfig;
import org.dbdatasync.sdk.connector.DefaultConnectorServiceContext;
import org.dbdatasync.sdk.enums.ModelEnum;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.MetaInfo;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.spi.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 异构数据库迁移/同步 预检测服务（产品化）
 *
 * <p>检测放在驱动/整库任务启动之前：存在 ERROR 级问题时拒绝启动，需先修复
 * （调整目标 DDL、为字段配置转换 Convert、修正映射）再复检，通过后才允许执行；
 * WARN 级问题只提示不阻断。</p>
 *
 * <p>检测规则（业界共识基线，报告《TEST_REPORT》预检测章节内附依据说明）：
 * <ul>
 *   <li>PC101 字段类型不兼容（如 CLOB→INT、BLOB→VARCHAR、BLOB→DECIMAL）</li>
 *   <li>PC102 有损/需转换的类型（如 TIMESTAMP→DATE、DECIMAL(带小数)→INT、FLOAT→DECIMAL、STRING→数值），建议配置内置转换函数</li>
 *   <li>PC103 目标字段长度不足（字符串截断风险）</li>
 *   <li>PC104 增量同步缺主键（UPDATE/DELETE 无法按主键定位行，UPSERT 失效）</li>
 *   <li>PC105 字段名命中目标库保留字（需加引号或改名）</li>
 *   <li>PC107 表组未配置字段映射</li>
 *   <li>PC108 varchar 字节/字符语义差异（Oracle/达梦按字节计数，目标库按字符）</li>
 * </ul></p>
 *
 * <p>修复闭环：预检测提示中引用的内置转换函数（ConvertEnum 编码）可通过表组编辑的
 * 转换配置（convert）下发，驱动启动时在 {@code ConvertUtil.convert} 链路生效；
 * 类型列超出内置函数覆盖范围时建议人工调整目标端 DDL 后复检。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-10-17
 */
@Service
public class PreCheckService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    public static final String TYPE_MAPPING = "mapping";
    public static final String TYPE_DATABASE_SYNC = "database-sync";

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private TaskService<DatabaseSyncTask> taskService;

    @Resource
    private ParserComponent parserComponent;

    /** 类型别名 → 规范类别 */
    private static final Map<String, String> TYPE_ALIASES = new HashMap<>();

    static {
        alias(TYPE_ALIASES, "STRING", "varchar", "varchar2", "nvarchar", "nvarchar2", "char", "nchar",
                "character varying", "character", "bpchar", "text", "tinytext", "mediumtext", "string",
                "uuid", "enum", "char2");
        alias(TYPE_ALIASES, "CLOB", "clob", "nclob", "longtext", "long varchar");
        alias(TYPE_ALIASES, "BLOB", "blob", "tinyblob", "mediumblob", "longblob", "bytea", "raw", "long raw",
                "binary", "varbinary", "binary varying", "image");
        alias(TYPE_ALIASES, "INT", "tinyint", "smallint", "int2", "int", "integer", "int4", "mediumint",
                "serial", "year");
        alias(TYPE_ALIASES, "LONG", "bigint", "int8", "bigserial", "identity");
        alias(TYPE_ALIASES, "DECIMAL", "numeric", "decimal", "number", "money", "dec");
        alias(TYPE_ALIASES, "FLOAT", "real", "float4", "float", "double", "double precision", "float8",
                "binary_float", "binary_double");
        alias(TYPE_ALIASES, "BOOLEAN", "bool", "boolean", "bit");
        alias(TYPE_ALIASES, "DATE", "date");
        alias(TYPE_ALIASES, "TIME", "time", "timetz", "time without time zone");
        alias(TYPE_ALIASES, "DATETIME", "datetime");
        alias(TYPE_ALIASES, "TIMESTAMP", "timestamp", "timestamptz", "timestamp with time zone",
                "timestamp without time zone", "timestamp with local time zone");
        alias(TYPE_ALIASES, "JSON", "json", "jsonb");
        alias(TYPE_ALIASES, "OTHER", "interval", "geometry", "geography", "array", "cidr", "macaddr",
                "rowid", "urowid", "anytype", "xmltype", "sref cursor");
    }

    private static void alias(Map<String, String> map, String category, String... names) {
        for (String name : names) {
            map.put(name, category);
        }
    }

    /** 目标库部分保留字（命中加引号/改名建议） */
    private static final Map<String, Set<String>> RESERVED_WORDS = new HashMap<>();

    static {
        RESERVED_WORDS.put("MySQL", set("order", "group", "index", "key", "desc", "asc", "range", "exists",
                "limit", "partition", "procedure", "trigger", "schema", "lock", "values"));
        RESERVED_WORDS.put("Oracle", set("user", "level", "comment", "cluster", "size", "type", "mode",
                "session", "audit", "success", "identified"));
        RESERVED_WORDS.put("Dameng", RESERVED_WORDS.get("Oracle"));
        RESERVED_WORDS.put("PostgreSQL", set("user", "order", "group", "limit", "offset", "all", "analyze",
                "window", "grant"));
        RESERVED_WORDS.put("Kingbase", RESERVED_WORDS.get("PostgreSQL"));
    }

    private static Set<String> set(String... words) {
        return new HashSet<>(Arrays.asList(words));
    }

    /**
     * 预检测一条映射驱动
     */
    public List<PreCheckIssue> preCheckMapping(String mappingId) {
        Mapping mapping = profileComponent.getMapping(mappingId);
        if (mapping == null) {
            throw new BizException("The mapping id is invalid.");
        }
        Connector source = profileComponent.getConnector(mapping.getSourceConnectorId());
        Connector target = profileComponent.getConnector(mapping.getTargetConnectorId());
        Context ctx = new Context();
        ctx.taskType = TYPE_MAPPING;
        ctx.taskName = mapping.getName();
        ctx.sourceDbType = dbType(source);
        ctx.targetDbType = dbType(target);
        ctx.groups = listGroups(mappingId);
        ctx.sourceConnectorId = mapping.getSourceConnectorId();
        ctx.sourceDatabase = mapping.getSourceDatabase();
        ctx.sourceSchema = mapping.getSourceSchema();
        ctx.targetConnectorId = mapping.getTargetConnectorId();
        ctx.targetDatabase = mapping.getTargetDatabase();
        ctx.targetSchema = mapping.getTargetSchema();
        // 增量语义判定：非纯全量，且开启 UPDATE/DELETE 时必须能按主键定位
        boolean increment = !StringUtil.equals(ModelEnum.FULL.getCode(), mapping.getModel());
        ListenerConfig listener = mapping.getListener();
        boolean updateDelete = listener != null && (listener.isEnableUpdate() || listener.isEnableDelete());
        ctx.requirePk = increment && updateDelete;
        return check(ctx);
    }

    /**
     * 预检测整库迁移任务
     */
    public List<PreCheckIssue> preCheckDatabaseSync(String taskId) {
        DatabaseSyncTask task = taskService.get(taskId);
        if (task == null) {
            throw new BizException("The database-sync task id is invalid.");
        }
        Connector source = null;
        Connector target = null;
        if (!CollectionUtils.isEmpty(task.getDatabaseMappings())) {
            source = profileComponent.getConnector(task.getDatabaseMappings().get(0).getSourceConnectorId());
            target = profileComponent.getConnector(task.getDatabaseMappings().get(0).getTargetConnectorId());
        }
        Context ctx = new Context();
        ctx.taskType = TYPE_DATABASE_SYNC;
        ctx.taskName = task.getName();
        ctx.sourceDbType = dbType(source);
        ctx.targetDbType = dbType(target);
        ctx.groups = listGroups(taskId);
        ctx.requirePk = false;
        if (!CollectionUtils.isEmpty(task.getDatabaseMappings())) {
            org.dbdatasync.sdk.model.DatabaseMapping firstMapping = task.getDatabaseMappings().get(0);
            ctx.sourceConnectorId = firstMapping.getSourceConnectorId();
            ctx.sourceDatabase = firstMapping.getSourceDatabase();
            ctx.sourceSchema = firstMapping.getSourceSchema();
            ctx.targetConnectorId = firstMapping.getTargetConnectorId();
            ctx.targetDatabase = firstMapping.getTargetDatabase();
            ctx.targetSchema = firstMapping.getTargetSchema();
        }
        return check(ctx);
    }

    /**
     * 启动前校验：存在 ERROR 级问题时抛出 BizException（阻止启动）
     */
    public void assertPassable(String taskType, String taskId) {
        List<PreCheckIssue> issues = TYPE_DATABASE_SYNC.equals(taskType)
                ? preCheckDatabaseSync(taskId) : preCheckMapping(taskId);
        List<PreCheckIssue> errors = new ArrayList<>();
        for (PreCheckIssue issue : issues) {
            if (PreCheckIssue.LEVEL_ERROR.equals(issue.getLevel())) {
                errors.add(issue);
            }
        }
        if (!CollectionUtils.isEmpty(errors)) {
            StringBuilder msg = new StringBuilder();
            msg.append("预检测未通过（").append(errors.size()).append("个阻断项），请修复后重试；")
                    .append("预检测详情见 /mapping/precheck 或 /database-sync/precheck。");
            int max = Math.min(errors.size(), 10);
            for (int i = 0; i < max; i++) {
                PreCheckIssue e = errors.get(i);
                msg.append("\n[").append(e.getCode()).append("] ").append(e.getTable());
                if (StringUtil.isNotBlank(e.getColumn())) {
                    msg.append(".").append(e.getColumn());
                }
                msg.append(": ").append(e.getMessage());
                if (StringUtil.isNotBlank(e.getSuggestion())) {
                    msg.append(" 建议: ").append(e.getSuggestion());
                }
            }
            throw new BizException(msg.toString());
        }
        int warns = issues.size() - errors.size();
        if (warns > 0) {
            logger.warn("预检测通过，存在 {} 个 warn 提示: task={}/{}", warns, taskType, taskId);
        }
    }

    private List<PreCheckIssue> check(Context ctx) {
        List<PreCheckIssue> issues = new ArrayList<>();
        if (CollectionUtils.isEmpty(ctx.groups)) {
            issues.add(PreCheckIssue.error("PC107", "表映射为空", ctx.taskName, "", "", "",
                    "任务未配置任何表映射，无法执行", "请先添加表映射"));
            return issues;
        }
        for (TableGroup group : ctx.groups) {
            // 复检语义：目标端元数据现场刷新（修复目标 DDL 后无需重启即可复检闭环）
            refreshTargetColumns(ctx, group);
            String tableName = (group.getSourceTable() != null ? group.getSourceTable().getName() : "?")
                    + " → " + (group.getTargetTable() != null ? group.getTargetTable().getName() : "?");
            if (CollectionUtils.isEmpty(group.getFieldMapping())) {
                if (TYPE_DATABASE_SYNC.equals(ctx.taskType)) {
                    // 整库同步：导入时按同名字段自动生成字段映射；预检测按推断映射做类型兼容检查，
                    // 无法推断（表字段缺失）时降级为 WARN 提示（不阻断整库流程）
                    List<FieldMapping> inferred = inferFieldMappings(group);
                    if (!inferred.isEmpty()) {
                        emitPairs(ctx, issues, tableName, group, inferred);
                    } else {
                        issues.add(PreCheckIssue.warn("PC107", "字段映射为空", tableName, "", "", "",
                                "整库同步表组未配置字段映射，导入时将按同名字段自动生成；本次预检测无法评估字段类型兼容性",
                                "建议先在表组编辑中确认字段映射后复检"));
                    }
                } else {
                    issues.add(PreCheckIssue.error("PC107", "字段映射为空", tableName, "", "", "",
                            "表组未配置字段映射", "请编辑表组补充字段映射"));
                }
                continue;
            }
            emitPairs(ctx, issues, tableName, group, group.getFieldMapping());
        }
        return issues;
    }

    /**
     * 按字段映射列表逐对做兼容性检查（含主键检查）。
     */
    private void emitPairs(Context ctx, List<PreCheckIssue> issues, String tableName, TableGroup group,
                           List<FieldMapping> fieldMappings) {
        for (FieldMapping mapping : fieldMappings) {
            Field source = mapping.getSource();
            Field target = mapping.getTarget();
            if (source == null || target == null) {
                issues.add(PreCheckIssue.error("PC107", "字段映射不完整", tableName,
                        source != null ? source.getName() : "?", "", "",
                        "字段映射缺少源或目标字段定义", "请重新编辑字段映射"));
                continue;
            }
            String column = source.getName() + " → " + target.getName();
            checkPair(ctx, issues, tableName, column, source, target, group.getSourceTable(), group.getTargetTable());
        }
        if (ctx.requirePk && !hasPk(group)) {
            issues.add(PreCheckIssue.error("PC104", "增量缺主键", tableName, "", "", "",
                    "增量同步开启 UPDATE/DELETE 但表组未标记主键字段（无法按主键定位行，UPSERT 会失效）",
                    "在字段映射中标记主键列（源/目标都要），或关闭 UPDATE/DELETE"));
        }
    }

    /**
     * 整库同步场景：按字段同名（忽略大小写）推断字段映射，用于预检测类型兼容性。
     */
    private List<FieldMapping> inferFieldMappings(TableGroup group) {
        List<FieldMapping> inferred = new ArrayList<>();
        Table source = group.getSourceTable();
        Table target = group.getTargetTable();
        if (source == null || target == null) {
            return inferred;
        }
        List<Field> sourceColumns = source.getColumn();
        List<Field> targetColumns = target.getColumn();
        if (CollectionUtils.isEmpty(sourceColumns) || CollectionUtils.isEmpty(targetColumns)) {
            return inferred;
        }
        Map<String, Field> targetIndex = new HashMap<>();
        for (Field field : targetColumns) {
            targetIndex.put(field.getName().toUpperCase(Locale.ROOT), field);
        }
        for (Field field : sourceColumns) {
            Field targetField = targetIndex.get(field.getName().toUpperCase(Locale.ROOT));
            if (targetField != null) {
                inferred.add(new FieldMapping(field, targetField));
            }
        }
        return inferred;
    }

    private void checkPair(Context ctx, List<PreCheckIssue> issues, String tableName, String column,
                           Field source, Field target, Table sourceTable, Table targetTable) {
        String sourceCategory = categoryOf(source.getTypeName());
        String targetCategory = categoryOf(target.getTypeName());
        String rawSource = source.getTypeName();
        String rawTarget = target.getTypeName();

        if (!"OTHER".equals(sourceCategory) && !"OTHER".equals(targetCategory) && !sourceCategory.equals(targetCategory)) {
            Verdict verdict = verdict(sourceCategory, targetCategory, source.getRatio());
            if (verdict.error) {
                issues.add(PreCheckIssue.error("PC101", "类型不兼容", tableName, column, rawSource, rawTarget,
                        verdict.message, verdict.suggestion));
            } else if (verdict.message != null) {
                issues.add(PreCheckIssue.warn("PC102", "有损/需转换", tableName, column, rawSource, rawTarget,
                        verdict.message, verdict.suggestion));
            }
        }

        // 字符串长度校验（含字节/字符语义提示）
        if ("STRING".equals(sourceCategory) && "STRING".equals(targetCategory)
                && source.getColumnSize() > 0 && target.getColumnSize() > 0) {
            int srcSize = source.getColumnSize();
            int tgtSize = target.getColumnSize();
            if (tgtSize < srcSize) {
                issues.add(PreCheckIssue.error("PC103", "长度不足", tableName, column, rawSource + "(" + srcSize + ")",
                        rawTarget + "(" + tgtSize + ")",
                        "目标字段长度小于源端，存在截断风险", "扩容目标字段长度，或处理截断后再同步"));
            } else if (isByteSemanticDB(ctx.sourceDbType) && (long) srcSize * 2 > tgtSize) {
                issues.add(PreCheckIssue.warn("PC108", "字符语义差异", tableName, column, rawSource + "(" + srcSize + ")",
                        rawTarget + "(" + tgtSize + ")",
                        "源端长度按字节计（多字节字符集最坏情况按 2~4 字节/字符），目标长度可能不够",
                        "建议目标端长度按源端 ×4 预留，或确认两端字符集一致"));
            }
        }

        // 目标字段名命中保留字
        Set<String> reserved = RESERVED_WORDS.get(ctx.targetDbType);
        if (reserved != null && target.getName() != null && reserved.contains(target.getName().toLowerCase(Locale.ROOT))) {
            issues.add(PreCheckIssue.warn("PC105", "保留字", tableName, column, rawSource, rawTarget,
                    "字段名 \"" + target.getName() + "\" 是 " + ctx.targetDbType + " 保留字",
                    "建议目标字段加转义引号或改名"));
        }
    }

    private boolean isByteSemanticDB(String dbType) {
        return StringUtil.equals("Oracle", dbType) || StringUtil.equals("Dameng", dbType);
    }

    /**
     * 现场刷新目标端表字段元数据（修复目标 DDL 后复检能读到新类型/长度），并保持主键与字段映射对齐
     */
    private void refreshTargetColumns(Context ctx, TableGroup group) {
        Table targetTable = group.getTargetTable();
        boolean success = false;
        String targetConnectorId = firstNotBlank(group.getTargetConnectorId(), ctx.targetConnectorId);
        if (targetTable != null && StringUtil.isNotBlank(targetTable.getName())
                && StringUtil.isNotBlank(targetConnectorId)) {
            try {
                DefaultConnectorServiceContext ctxConn = ConnectorServiceContextUtil.buildConnectorServiceContext(
                        group.getTaskId(),
                        firstNotBlank(group.getSourceConnectorId(), ctx.sourceConnectorId),
                        firstNotBlank(group.getSourceDatabase(), ctx.sourceDatabase),
                        firstNotBlank(group.getSourceSchema(), ctx.sourceSchema),
                        targetConnectorId,
                        firstNotBlank(group.getTargetDatabase(), ctx.targetDatabase),
                        firstNotBlank(group.getTargetSchema(), ctx.targetSchema), false);
                ctxConn.addTablePattern(targetTable);
                List<MetaInfo> metaInfos = parserComponent.getMetaInfo(ctxConn);
                logger.info("precheck refresh target meta: table={}, db={}, schema={}, fields={}",
                        targetTable.getName(), ctxConn.getCatalog(), ctxConn.getSchema(),
                        metaInfos == null ? -1 : (metaInfos.isEmpty() ? 0 : metaInfos.get(0).getColumn().size()));
                if (!CollectionUtils.isEmpty(metaInfos) && !CollectionUtils.isEmpty(metaInfos.get(0).getColumn())) {
                    List<Field> fresh = metaInfos.get(0).getColumn();
                    // 保留旧主键标记
                    Map<String, Boolean> oldPks = new HashMap<>();
                    if (!CollectionUtils.isEmpty(targetTable.getColumn())) {
                        for (Field old : targetTable.getColumn()) {
                            if (old.isPk()) {
                                oldPks.put(old.getName().toUpperCase(Locale.ROOT), true);
                            }
                        }
                    }
                    for (Field field : fresh) {
                        if (oldPks.containsKey(field.getName().toUpperCase(Locale.ROOT))) {
                            field.setPk(true);
                        }
                    }
                    targetTable.setColumn(fresh);
                    // 字段映射对齐到新 Field
                    Map<String, Field> idx = new HashMap<>();
                    for (Field field : fresh) {
                        idx.put(field.getName().toUpperCase(Locale.ROOT), field);
                    }
                    if (!CollectionUtils.isEmpty(group.getFieldMapping())) {
                        for (FieldMapping fm : group.getFieldMapping()) {
                            Field target = fm.getTarget();
                            if (target != null) {
                                Field newField = idx.get(target.getName().toUpperCase(Locale.ROOT));
                                if (newField != null) {
                                    fm.setTarget(newField);
                                }
                            }
                        }
                    }
                    success = true;
                }
            } catch (Exception e) {
                logger.warn("precheck refresh target columns failed: table={}, error={}",
                        targetTable.getName(), e.getMessage(), e);
            }
        }
        if (!success) {
            logger.info("precheck uses cached target columns: table={}",
                    targetTable != null ? targetTable.getName() : "?");
        }
    }

    private String firstNotBlank(String a, String b) {
        return StringUtil.isNotBlank(a) ? a : b;
    }

    private boolean hasPk(TableGroup group) {
        Table sourceTable = group.getSourceTable();
        if (sourceTable != null && !CollectionUtils.isEmpty(sourceTable.getColumn())) {
            for (Field f : sourceTable.getColumn()) {
                if (f.isPk()) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<TableGroup> listGroups(String taskId) {
        List<String> ids = tableGroupProfile.listTableGroupIds(taskId);
        List<TableGroup> groups = new ArrayList<>(ids.size());
        for (String id : ids) {
            TableGroup group = tableGroupProfile.getTableGroup(id);
            if (group != null) {
                groups.add(group);
            }
        }
        return groups;
    }

    private String dbType(Connector connector) {
        if (connector != null && connector.getConfig() != null
                && StringUtil.isNotBlank(connector.getConfig().getConnectorType())) {
            return connector.getConfig().getConnectorType();
        }
        return "";
    }

    /**
     * 类别到类别的兼容判定
     */
    private Verdict verdict(String sourceCategory, String targetCategory, int sourceRatio) {
        // TEXT/大文本类
        if ("CLOB".equals(sourceCategory)) {
            if ("CLOB".equals(targetCategory) || "STRING".equals(targetCategory)) {
                return Verdict.warn("大文本转受限字符串长度时存在截断风险，评估目标长度",
                        "目标类型设为 TEXT/CLOB；如必须 toArray 请评估长度");
            }
            return Verdict.error("大文本无法落入数值/时间/二进制类字段",
                    "调整目标类型为 TEXT/CLOB，或人工截断转换");
        }
        if ("BLOB".equals(sourceCategory)) {
            if ("BLOB".equals(targetCategory)) {
                return Verdict.ok();
            }
            return Verdict.error("二进制数据无法直接落入非二进制字段",
                    "目标类型改为 BLOB/BINARY；如需文本化，为字段配置 BYTES_TO_STRING 转换并将目标改为 TEXT");
        }
        // 数值类
        if ("INT".equals(sourceCategory)) {
            if ("LONG".equals(targetCategory) || "DECIMAL".equals(targetCategory) || "INT".equals(targetCategory)) {
                return Verdict.ok();
            }
            if ("FLOAT".equals(targetCategory) || "BOOLEAN".equals(targetCategory)
                    || "STRING".equals(targetCategory)) {
                return Verdict.warn("整型转 " + targetCategory + " 需确认编码约定（如布尔 1/0）",
                        "建议内容可转数值，或配置 NUMBER_TO_STRING");
            }
            if ("DATE".equals(targetCategory) || "TIME".equals(targetCategory)
                    || "DATETIME".equals(targetCategory) || "TIMESTAMP".equals(targetCategory)) {
                return Verdict.error("整型无法直接转时间类型", "人工确认语义（如 unix 秒）后配置转换或改目标类型");
            }
        }
        if ("LONG".equals(sourceCategory)) {
            if ("LONG".equals(targetCategory) || "DECIMAL".equals(targetCategory) || "INT".equals(targetCategory)) {
                return Verdict.warn("整型转更低精度整型存在溢出/精度风险", "确认源端取值范围小于目标类型，或扩容目标类型");
            }
            if ("FLOAT".equals(targetCategory) || "BOOLEAN".equals(targetCategory) || "STRING".equals(targetCategory)) {
                return Verdict.warn("大整型转 " + targetCategory + " 需确认转换规则", "建议配置合适的数值转换");
            }
            return Verdict.error("大整型无法直接转 " + targetCategory, "调整目标类型或人工配置转换");
        }
        if ("DECIMAL".equals(sourceCategory)) {
            if ("DECIMAL".equals(targetCategory)) {
                return Verdict.ok();
            }
            if ("FLOAT".equals(targetCategory)) {
                return Verdict.warn("高精度小数转浮点存在精度损失", "评估业务精度要求，或调整目标类型为 DECIMAL");
            }
            if ("INT".equals(targetCategory) || "LONG".equals(targetCategory)) {
                if (sourceRatio > 0) {
                    return Verdict.error("带小数的数值转整型会丢失精度", "调整目标类型为 DECIMAL，或配置取整转换");
                }
                return Verdict.warn("整数范围内 DECIMAL 转整型可行", "确认无小数位后可执行");
            }
            if ("STRING".equals(targetCategory)) {
                return Verdict.warn("数值转字符串可行", "如需固定位数格式，配置 NUMBER_TO_STRING");
            }
        }
        if ("FLOAT".equals(sourceCategory)) {
            if ("FLOAT".equals(targetCategory)) {
                return Verdict.ok();
            }
            if ("DECIMAL".equals(targetCategory)) {
                return Verdict.warn("浮点转高精度小数需确认精度/舍入", "调整目标精度或人工确认");
            }
            if ("INT".equals(targetCategory) || "LONG".equals(targetCategory)) {
                return Verdict.error("浮点转整型会丢失小数与精度", "调整目标类型为 DECIMAL，或配置取整转换");
            }
            if ("STRING".equals(targetCategory)) {
                return Verdict.warn("浮点转字符串可行", "可配置 NUMBER_TO_STRING 固定格式");
            }
        }
        // 字符串类
        if ("STRING".equals(sourceCategory)) {
            if ("STRING".equals(targetCategory) || "CLOB".equals(targetCategory)) {
                return Verdict.ok();
            }
            if ("INT".equals(targetCategory) || "LONG".equals(targetCategory)
                    || "DECIMAL".equals(targetCategory) || "FLOAT".equals(targetCategory)) {
                return Verdict.warn("字符串转数值需要内容校验", "确保内容均为合法数值，否则建议保留 STRING");
            }
            if ("DATE".equals(targetCategory) || "TIME".equals(targetCategory)
                    || "DATETIME".equals(targetCategory) || "TIMESTAMP".equals(targetCategory)) {
                return Verdict.warn("字符串转时间需确认格式",
                        "配置 STRING_TO_FORMAT_DATE（自定义格式）或 STRING_TO_TIMESTAMP 转换");
            }
            if ("BOOLEAN".equals(targetCategory)) {
                return Verdict.warn("字符串转布尔需确认取值（true/false、1/0）", "人工约定映射或配置替换转换");
            }
        }
        // 数值→字符串 ok（上面没命中 default）
        if (("INT".equals(sourceCategory) || "LONG".equals(sourceCategory) || "DECIMAL".equals(sourceCategory)
                || "FLOAT".equals(sourceCategory) || "BOOLEAN".equals(sourceCategory))
                && "STRING".equals(targetCategory)) {
            return Verdict.warn("数值/布尔转字符串可行", "可配置 NUMBER_TO_STRING 统一格式");
        }
        // 时间类
        if ("DATE".equals(sourceCategory)) {
            if ("DATE".equals(targetCategory) || "DATETIME".equals(targetCategory)
                    || "TIMESTAMP".equals(targetCategory)) {
                return Verdict.ok();
            }
            if ("TIME".equals(targetCategory)) {
                return Verdict.warn("DATE 转 TIME 只保留时间部分", "确认语义");
            }
            if ("STRING".equals(targetCategory)) {
                return Verdict.warn("日期转字符串可行", "可配置 STRING_TO_FORMAT_DATE 处理格式");
            }
        }
        if ("TIME".equals(sourceCategory)) {
            if ("TIME".equals(targetCategory) || "DATETIME".equals(targetCategory)) {
                return Verdict.ok();
            }
            if ("DATE".equals(targetCategory)) {
                return Verdict.error("TIME 转 DATE 会丢失日期语义", "改目标为 TIME/DATETIME");
            }
            if ("STRING".equals(targetCategory)) {
                return Verdict.warn("时间转字符串可行", "确认格式");
            }
        }
        if ("DATETIME".equals(sourceCategory) || "TIMESTAMP".equals(sourceCategory)) {
            if ("DATETIME".equals(targetCategory) || "TIMESTAMP".equals(targetCategory)) {
                return Verdict.ok();
            }
            if ("DATE".equals(targetCategory)) {
                return Verdict.error("时间戳转 DATE 会丢失时间部分",
                        "调整目标为 DATETIME/TIMESTAMP；确需 DATE 时为字段配置 TIMESTAMP_TO_DATE 转换");
            }
            if ("LONG".equals(targetCategory) || "INT".equals(targetCategory)) {
                return Verdict.warn("时间戳转数值为 unix 时间，需确认单位",
                        "可配置 TIMESTAMP_TO_LONG 转换并确认毫秒/秒");
            }
            if ("STRING".equals(targetCategory)) {
                return Verdict.warn("时间戳转字符串可行", "可配置 TIMESTAMP_TO_CHINESE_STANDARD_TIME 或格式化转换");
            }
            if ("TIME".equals(targetCategory)) {
                return Verdict.warn("时间戳转 TIME 丢弃日期部分", "确认语义");
            }
        }
        if ("BOOLEAN".equals(sourceCategory)) {
            if ("BOOLEAN".equals(targetCategory) || "INT".equals(targetCategory)
                    || "STRING".equals(targetCategory)) {
                return Verdict.warn("布尔转 " + targetCategory + " 需确认存储约定（1/0 或 true/false）", "人工确认");
            }
        }
        if ("JSON".equals(sourceCategory)) {
            if ("JSON".equals(targetCategory) || "STRING".equals(targetCategory) || "CLOB".equals(targetCategory)) {
                return Verdict.warn("JSON 转 " + targetCategory + " 可行", "确认目标长度足够");
            }
            return Verdict.error("JSON 无法直接转 " + targetCategory, "调整目标为 JSON/TEXT 或人工反序列化");
        }
        if ("OTHER".equals(sourceCategory)) {
            if ("STRING".equals(targetCategory) || "CLOB".equals(targetCategory)) {
                return Verdict.warn("未识别类型转字符串可尝试", "确认可序列化；如失败请调整映射");
            }
        }
        return Verdict.error("类别 " + sourceCategory + " → " + targetCategory + " 无自动兼容规则",
                "调整目标类型，或为该字段配置合适的转换函数后复检");
    }

    /**
     * 类型类别归一：JDBC TYPE_NAME → 规范类别
     */
    public static String categoryOf(String typeName) {
        if (StringUtil.isBlank(typeName)) {
            return "OTHER";
        }
        String full = typeName.toLowerCase(Locale.ROOT).trim();
        if (TYPE_ALIASES.containsKey(full)) {
            return TYPE_ALIASES.get(full);
        }
        String lower = full;
        int paren = lower.indexOf('(');
        if (paren > 0) {
            lower = lower.substring(0, paren).trim();
        }
        if (TYPE_ALIASES.containsKey(lower)) {
            return TYPE_ALIASES.get(lower);
        }
        // LIKE 'char x'
        int space = lower.indexOf(' ');
        if (space > 0) {
            String head = lower.substring(0, space).trim();
            if (TYPE_ALIASES.containsKey(head)) {
                return TYPE_ALIASES.get(head);
            }
        }
        return "OTHER";
    }

    private static class Context {
        String taskType;
        String taskName;
        String sourceDbType;
        String targetDbType;
        List<TableGroup> groups;
        boolean requirePk;
        // 任务级库连接信息（mapping 型任务在 Mapping 上携带；整库任务取第一条库映射兜底）
        String sourceConnectorId;
        String sourceDatabase;
        String sourceSchema;
        String targetConnectorId;
        String targetDatabase;
        String targetSchema;
    }

    private static final class Verdict {
        final boolean error;
        final String message;
        final String suggestion;

        private Verdict(boolean error, String message, String suggestion) {
            this.error = error;
            this.message = message;
            this.suggestion = suggestion;
        }

        static Verdict ok() {
            return new Verdict(false, null, null);
        }

        static Verdict warn(String message, String suggestion) {
            return new Verdict(false, message, suggestion);
        }

        static Verdict error(String message, String suggestion) {
            return new Verdict(true, message, suggestion);
        }
    }
}
