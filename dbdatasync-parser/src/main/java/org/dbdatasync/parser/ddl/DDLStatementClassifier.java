package org.dbdatasync.parser.ddl;

import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.sdk.enums.DDLOperationEnum;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 表级 DDL 识别器（Wave23）。
 *
 * <p>现有 {@code DDLParserImpl} 只覆盖 4 类列级 ALTER；本类把
 * CREATE / DROP / TRUNCATE / RENAME / 索引与约束语句纳入识别范围，
 * 让运维能看到「发生了什么 DDL」，并明确区分可自动同步与必须人工确认的类型。</p>
 *
 * <p><b>安全边界</b>：识别结果只用于分类、告警与审计，绝不直接生成目标端
 * 可执行 SQL。表级 DDL 涉及对象重建、数据清空与依赖顺序，自动执行风险极高，
 * 仍需在控制台人工确认后由迁移编排处理。</p>
 */
public final class DDLStatementClassifier {

    private static final Pattern CREATE_TABLE = Pattern.compile("^\\s*CREATE\\s+(OR\\s+REPLACE\\s+)?(GLOBAL\\s+TEMPORARY\\s+|TEMPORARY\\s+|UNLOGGED\\s+)?TABLE\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TABLE = Pattern.compile("^\\s*DROP\\s+TABLE\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRUNCATE = Pattern.compile("^\\s*TRUNCATE\\s+(TABLE\\s+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME = Pattern.compile("^\\s*(RENAME\\s+TABLE\\s+|ALTER\\s+TABLE\\s+.+\\s+RENAME\\s+TO\\s+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX = Pattern.compile("^\\s*CREATE\\s+(UNIQUE\\s+)?(BITMAP\\s+)?INDEX\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_INDEX = Pattern.compile("^\\s*DROP\\s+INDEX\\s+", Pattern.CASE_INSENSITIVE);
    // 必须显式出现约束关键字：否则 "ALTER TABLE t ADD COLUMN c ..." 会被误判成表级 DDL，
    // 进而让原本可自动同步的加字段语句被 DDLParserImpl 提前拦截。
    private static final Pattern ADD_CONSTRAINT = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+.+\\s+ADD\\s+(CONSTRAINT|PRIMARY\\s+KEY|UNIQUE|FOREIGN\\s+KEY|CHECK)\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_CONSTRAINT = Pattern.compile("^\\s*ALTER\\s+TABLE\\s+.+\\s+DROP\\s+(CONSTRAINT|PRIMARY\\s+KEY|UNIQUE)\\s+", Pattern.CASE_INSENSITIVE);

    private DDLStatementClassifier() {
    }

    /** 返回表级 DDL 类型；列级 ALTER 或 DML 返回 null，交由原解析器处理 */
    public static DDLOperationEnum classifyTableLevel(String sql) {
        if (StringUtil.isBlank(sql)) {
            return null;
        }
        String text = sql.trim().toUpperCase(Locale.ROOT);
        if (text.startsWith("CREATE") && CREATE_TABLE.matcher(sql).find()) {
            return DDLOperationEnum.CREATE_TABLE;
        }
        if (CREATE_INDEX.matcher(sql).find() && !CREATE_TABLE.matcher(sql).find()) {
            return DDLOperationEnum.CREATE_INDEX;
        }
        if (DROP_TABLE.matcher(sql).find()) {
            return DDLOperationEnum.DROP_TABLE;
        }
        if (DROP_INDEX.matcher(sql).find()) {
            return DDLOperationEnum.DROP_INDEX;
        }
        if (TRUNCATE.matcher(sql).find()) {
            return DDLOperationEnum.TRUNCATE_TABLE;
        }
        if (RENAME.matcher(sql).find()) {
            return DDLOperationEnum.RENAME_TABLE;
        }
        if (DROP_CONSTRAINT.matcher(sql).find()) {
            return DDLOperationEnum.DROP_CONSTRAINT;
        }
        if (ADD_CONSTRAINT.matcher(sql).find()) {
            return DDLOperationEnum.ADD_CONSTRAINT;
        }
        return null;
    }

    /** 这些类型会清空/重建对象，必须人工确认 */
    public static boolean requiresManualConfirmation(DDLOperationEnum operation) {
        return operation == DDLOperationEnum.CREATE_TABLE
                || operation == DDLOperationEnum.DROP_TABLE
                || operation == DDLOperationEnum.TRUNCATE_TABLE
                || operation == DDLOperationEnum.RENAME_TABLE
                || operation == DDLOperationEnum.DROP_CONSTRAINT;
    }
}
