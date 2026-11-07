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
 * ADD / MODIFY / CHANGE / DROP / RENAME 单列操作单元，并对 ADD/MODIFY/CHANGE
 * 做类型映射（只读复用 {@link DdlTypeMapping}）。解析失败降级为 {@code UNKNOWN} 单元，
 * <strong>绝不抛出</strong>。</p>
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
