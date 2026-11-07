package org.dbdatasync.biz.sqlscan;

import org.dbdatasync.biz.sqlscan.SqlCompatibilityRules.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ALTER TABLE 变更拆解器（Wave13 B 线，纯离线正则拆解，不引 jsqlparser）。
 *
 * <p>与 {@link DdlDraftGenerator} 纯字符串风格一致：把 ALTER TABLE 语句拆解为
 * ADD / MODIFY / CHANGE / DROP / RENAME 单列操作单元，以及对 ADD CONSTRAINT /
 * ADD INDEX / DROP CONSTRAINT / SET DEFAULT 等复合子句（Wave15 扩展）识别为
 * 明确单元（含 {@code objectName}）；对 ADD/MODIFY/CHANGE 做类型映射
 * （只读复用 {@link DdlTypeMapping}）。解析失败或超出现有模式的子句降级为
 * {@code UNKNOWN} 单元，<strong>绝不抛出</strong>。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public final class DdlChangeSplitter {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    /** ALTER TABLE <表> 前缀，捕获剩余子句 */
    private static final Pattern P_ALTER =
            Pattern.compile("^\\s*ALTER\\s+TABLE\\s+[\\w$.]+\\b\\s*(.*)$", FLAGS);
    private static final Pattern P_CHANGE =
            Pattern.compile("CHANGE\\s+(COLUMN\\s+)?([\\w$]+)\\s+([\\w$]+)\\s+([^\\s,(]+(\\([^)]*\\))?)", FLAGS);
    private static final Pattern P_MODIFY =
            Pattern.compile("MODIFY\\s+(COLUMN\\s+)?([\\w$]+)\\s+([^\\s,(]+(\\([^)]*\\))?)", FLAGS);
    private static final Pattern P_RENAME =
            Pattern.compile("RENAME\\s+(COLUMN\\s+)?([\\w$]+)\\s+TO\\s+([\\w$]+)", FLAGS);
    private static final Pattern P_ADD =
            Pattern.compile("ADD\\s+(COLUMN\\s+)?([\\w$]+)\\s+([^\\s,(]+(\\([^)]*\\))?)", FLAGS);
    private static final Pattern P_DROP =
            Pattern.compile("DROP\\s+(COLUMN\\s+)?([\\w$]+)", FLAGS);

    /** Wave15 复合子句：ADD CONSTRAINT <name> (PRIMARY KEY|FOREIGN KEY|UNIQUE|CHECK) */
    private static final Pattern P_ADD_NAMED_CONSTRAINT =
            Pattern.compile("ADD\\s+CONSTRAINT\\s+([\\w$]+)\\s+(PRIMARY\\s+KEY|FOREIGN\\s+KEY|UNIQUE|CHECK)\\b", FLAGS);
    /** Wave15 复合子句：ADD [UNIQUE] INDEX <name> */
    private static final Pattern P_ADD_INDEX =
            Pattern.compile("ADD\\s+(UNIQUE\\s+)?INDEX\\s+([\\w$]+)", FLAGS);
    /** Wave15 复合子句：ADD KEY <name>（MySQL 风格） */
    private static final Pattern P_ADD_KEY =
            Pattern.compile("ADD\\s+KEY\\s+([\\w$]+)", FLAGS);
    /** Wave15 复合子句：ADD (PRIMARY KEY|FOREIGN KEY|UNIQUE(...)) 无名约束 */
    private static final Pattern P_ADD_UNNAMED_CONSTRAINT =
            Pattern.compile("ADD\\s+(PRIMARY\\s+KEY|FOREIGN\\s+KEY|UNIQUE(?=\\s*\\())\\b", FLAGS);
    /** Wave15 复合子句：ALTER [COLUMN] <col> SET DEFAULT <val> | DROP DEFAULT */
    private static final Pattern P_ALTER_COLUMN_DEFAULT =
            Pattern.compile("ALTER\\s+(COLUMN\\s+)?([\\w$]+)\\s+(SET\\s+DEFAULT\\s+[^,;]+|DROP\\s+DEFAULT)", FLAGS);
    /** Wave15 复合子句：DROP CONSTRAINT|PRIMARY KEY|INDEX|FOREIGN KEY|KEY [name] */
    private static final Pattern P_DROP_OBJECT =
            Pattern.compile("DROP\\s+(CONSTRAINT|PRIMARY\\s+KEY|INDEX|FOREIGN\\s+KEY|KEY)\\s*([\\w$]*)", FLAGS);

    /** 类型映射表（只读复用，单例即可） */
    private static final DdlTypeMapping TYPE_MAPPING = new DdlTypeMapping();

    private DdlChangeSplitter() {
    }

    /**
     * 拆解 ALTER TABLE 语句为变更单元列表。
     *
     * @param alterSql      原始 ALTER SQL（可多条，分号分隔）
     * @param sourceDialect 源方言（保留契约参数，本轮映射与方言无关）
     * @param targetDialect 目标方言（保留契约参数，本轮映射与方言无关）
     * @return 变更单元列表；空/纯空白输入返回空列表；异常返回含单条 UNKNOWN 的列表
     */
    public static List<DdlChangeUnit> split(String alterSql, String sourceDialect, String targetDialect) {
        try {
            List<DdlChangeUnit> units = new ArrayList<>();
            if (alterSql == null || alterSql.trim().isEmpty()) {
                return units;
            }
            String cleaned = stripComments(alterSql);
            String[] stmts = cleaned.split(";");
            for (String raw : stmts) {
                String stmt = raw == null ? "" : raw.trim();
                if (stmt.isEmpty()) {
                    continue;
                }
                units.add(parseStatement(stmt));
            }
            return units;
        } catch (Exception e) {
            List<DdlChangeUnit> fallback = new ArrayList<>();
            fallback.add(unknownUnit("无法解析，请人工确认"));
            return fallback;
        }
    }

    private static DdlChangeUnit parseStatement(String stmt) {
        Matcher alter = P_ALTER.matcher(stmt);
        if (!alter.find()) {
            return unknownUnit("无法解析，请人工确认");
        }
        String clause = alter.group(1) == null ? "" : alter.group(1).trim();
        if (clause.isEmpty()) {
            return unknownUnit("无法解析，请人工确认");
        }
        // 顺序优先 CHANGE > MODIFY > RENAME > ADD > DROP，避免 ADD 误吞 CHANGE 中的 COLUMN 关键字
        Matcher m;
        if ((m = P_CHANGE.matcher(clause)).find()) {
            return buildColumnUnit("CHANGE", m.group(2), m.group(3), m.group(4));
        }
        if ((m = P_MODIFY.matcher(clause)).find()) {
            return buildColumnUnit("MODIFY", null, m.group(2), m.group(3));
        }
        if ((m = P_RENAME.matcher(clause)).find()) {
            DdlChangeUnit u = new DdlChangeUnit();
            u.setOperation("RENAME");
            u.setOldName(m.group(2));
            u.setColumnName(m.group(3));
            u.setSourceType("");
            u.setTargetType("");
            u.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
            u.setRiskNote("仅重命名，请确认目标端列名语义");
            return u;
        }
        // ---- Wave15 复合子句识别（严格排在通用 ADD/DROP 列之前，避免误判为列操作） ----
        if ((m = P_ADD_NAMED_CONSTRAINT.matcher(clause)).find()) {
            return buildConstraintUnit(m.group(1), normalizeConstraintType(m.group(2)), true);
        }
        if ((m = P_ADD_INDEX.matcher(clause)).find()) {
            boolean unique = m.group(1) != null
                    && m.group(1).trim().toUpperCase(Locale.ROOT).startsWith("UNIQUE");
            return buildIndexUnit(m.group(2), unique, false);
        }
        if ((m = P_ADD_KEY.matcher(clause)).find()) {
            return buildIndexUnit(m.group(1), false, true);
        }
        if ((m = P_ADD_UNNAMED_CONSTRAINT.matcher(clause)).find()) {
            return buildConstraintUnit("", normalizeConstraintType(m.group(1)), false);
        }
        if ((m = P_ALTER_COLUMN_DEFAULT.matcher(clause)).find()) {
            String sub = m.group(3) == null ? "" : m.group(3).trim();
            if (sub.toUpperCase(Locale.ROOT).startsWith("DROP")) {
                return buildDefaultUnit(m.group(2), "DROP_DEFAULT", "删除列默认值，请确认目标端语义");
            }
            return buildDefaultUnit(m.group(2), "SET_DEFAULT", "设置列默认值，请确认目标端默认值语义");
        }
        if ((m = P_DROP_OBJECT.matcher(clause)).find()) {
            String kind = m.group(1) == null ? "" : m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
            String name = m.group(2) == null ? "" : m.group(2).trim();
            if ("PRIMARY_KEY".equals(kind)) {
                return buildDropObjectUnit("DROP_PRIMARY_KEY", "", "删除主键，高危，请确认目标端无下游依赖");
            } else if ("INDEX".equals(kind) || "KEY".equals(kind) || "FOREIGN_KEY".equals(kind)) {
                return buildDropObjectUnit("DROP_INDEX", name, "删除索引/外键，请确认目标端无下游依赖");
            }
            return buildDropObjectUnit("DROP_CONSTRAINT", name, "删除约束，请确认目标端无下游依赖");
        }
        if ((m = P_ADD.matcher(clause)).find()) {
            return buildColumnUnit("ADD", null, m.group(2), m.group(3));
        }
        if ((m = P_DROP.matcher(clause)).find()) {
            DdlChangeUnit u = new DdlChangeUnit();
            u.setOperation("DROP");
            u.setOldName("");
            u.setColumnName(m.group(2));
            u.setSourceType("(drop)");
            u.setTargetType("(drop)");
            u.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
            u.setRiskNote("删除列，请确认目标端无下游依赖");
            return u;
        }
        return unknownUnit("无法解析，请人工确认");
    }

    private static DdlChangeUnit buildColumnUnit(String op, String oldName,
                                                String columnName, String sourceType) {
        DdlChangeUnit u = new DdlChangeUnit();
        u.setOperation(op);
        u.setOldName(oldName == null ? "" : oldName);
        u.setColumnName(columnName == null ? "" : columnName);
        u.setSourceType(sourceType == null ? "" : sourceType);

        String target = TYPE_MAPPING.map(u.getSourceType());
        u.setTargetType(target);

        if (TYPE_MAPPING.hasErrorLevel(u.getSourceType())) {
            u.setLevel(SqlCompatibilityIssue.LEVEL_ERROR);
            u.setRiskNote("error 级类型需人工重写");
        } else if (target != null && !target.equals(u.getSourceType())) {
            u.setLevel(SqlCompatibilityIssue.LEVEL_WARN);
            u.setRiskNote("类型已映射为目标方言等价类型，请人工复核");
        } else {
            u.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
            u.setRiskNote(lookupMessage(u.getSourceType()));
        }
        return u;
    }

    // ---- Wave15 复合子句单元构造 ----

    private static String normalizeConstraintType(String raw) {
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static DdlChangeUnit buildConstraintUnit(String name, String type, boolean named) {
        DdlChangeUnit u = new DdlChangeUnit();
        u.setOperation("ADD_CONSTRAINT");
        u.setObjectName(name == null ? "" : name);
        u.setColumnName("");
        u.setOldName("");
        u.setSourceType("");
        u.setTargetType("");
        u.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
        String suffix = named ? "，约束名 " + (name == null ? "" : name) : "，无显式名";
        u.setRiskNote("约束变更（" + type + suffix + "），请人工确认目标端语义（不自动执行）");
        return u;
    }

    private static DdlChangeUnit buildIndexUnit(String name, boolean unique, boolean isKey) {
        DdlChangeUnit u = new DdlChangeUnit();
        u.setOperation("ADD_INDEX");
        u.setObjectName(name == null ? "" : name);
        u.setColumnName("");
        u.setOldName("");
        u.setSourceType("");
        u.setTargetType("");
        u.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
        String kind = (unique ? "唯一 " : "") + (isKey ? "KEY" : "INDEX");
        u.setRiskNote(kind + "索引变更（索引名 " + (name == null ? "" : name) + "），请人工确认（不自动执行）");
        return u;
    }

    private static DdlChangeUnit buildDefaultUnit(String col, String op, String note) {
        DdlChangeUnit u = new DdlChangeUnit();
        u.setOperation(op);
        u.setColumnName(col == null ? "" : col);
        u.setObjectName("");
        u.setOldName("");
        u.setSourceType("");
        u.setTargetType("");
        u.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
        u.setRiskNote(note);
        return u;
    }

    private static DdlChangeUnit buildDropObjectUnit(String op, String name, String note) {
        DdlChangeUnit u = new DdlChangeUnit();
        u.setOperation(op);
        u.setObjectName(name == null ? "" : name);
        u.setColumnName("");
        u.setOldName("");
        u.setSourceType("");
        u.setTargetType("");
        u.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
        u.setRiskNote(note);
        return u;
    }

    /** 取 sourceType 小写命中的 TYPE 规则 message（用于 info 级风险说明） */
    private static String lookupMessage(String sourceType) {
        if (sourceType == null || sourceType.isEmpty()) {
            return "无映射命中";
        }
        String key = sourceType.toLowerCase(Locale.ROOT);
        for (Rule rule : SqlCompatibilityRules.allTypeRules()) {
            if ("type".equals(rule.category) && key.equals(rule.element)) {
                return rule.message;
            }
        }
        return "无映射命中";
    }

    private static DdlChangeUnit unknownUnit(String riskNote) {
        DdlChangeUnit u = new DdlChangeUnit();
        u.setOperation("UNKNOWN");
        u.setOldName("");
        u.setColumnName("");
        u.setObjectName("");
        u.setSourceType("");
        u.setTargetType("");
        u.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
        u.setRiskNote(riskNote);
        return u;
    }

    /** 去掉 -- 行注释与 \/\* \*\/ 块注释 */
    private static String stripComments(String sql) {
        String s = sql;
        s = s.replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        s = s.replaceAll("--[^\\n]*", " ");
        return s;
    }
}
