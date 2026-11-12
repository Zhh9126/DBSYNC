package org.dbdatasync.biz.sqlscan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDL 建表语句类型映射表（Wave10 Q3 唯一口径，纯字符串处理）。
 *
 * <p>映射规则（大小写不敏感、词边界）：
 * <ul>
 *   <li>{@code VARCHAR2(n) / NVARCHAR2(n) → VARCHAR(n)}</li>
 *   <li>裸 {@code VARCHAR2 / NVARCHAR2 → VARCHAR(255)}</li>
 *   <li>{@code NUMBER(p,s) → DECIMAL(p,s)}；{@code NUMBER(p) → DECIMAL(p,0)}</li>
 *   <li>裸 {@code NUMBER → DECIMAL(18,4)}（与 SQL-F05 CAST 示例一致）</li>
 *   <li>{@code CLOB → TEXT}（沿用 Wave8，不改 LONGTEXT）</li>
 *   <li>{@code BOOLEAN → TINYINT(1)}（与 SQL-T08 MySQL tinyint(1) 一致）</li>
 *   <li>{@code ROWID / UROWID}（SQL-T12/T13，error 级）<strong>不生成草稿</strong></li>
 * </ul>
 *
 * <p>级别取自 {@link SqlCompatibilityRules#allTypeRules()}：<strong>仅 warn 级类型参与映射</strong>，
 * error / info 级不改写。基础替换前缀只读复用 Wave8
 * {@link SqlRewriteDraft#baseTypeRewriteMap()}（{@code REWRITE_MAP} 静态块与 {@code draft()} 零改动）。
 * 全程无 JDBC / 无连接 / 不执行 DDL。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public final class DdlTypeMapping {

    /** 裸 VARCHAR2/NVARCHAR2 默认长度 */
    public static final int DEFAULT_VARCHAR_LEN = 255;

    /** 裸 NUMBER 默认精度 */
    public static final int NUMBER_P = 18;

    /** 裸 NUMBER 默认小数位 */
    public static final int NUMBER_S = 4;

    /** Wave8 基础替换表只读视图（不修改、不复制写） */
    private static final Map<String, String> BASE = SqlRewriteDraft.baseTypeRewriteMap();

    private static final String VARCHAR_PREFIX = BASE.getOrDefault("VARCHAR2(", "VARCHAR(");
    private static final String NUMBER_PREFIX = BASE.getOrDefault("NUMBER(", "DECIMAL(");
    private static final String CLOB_TARGET = BASE.getOrDefault("CLOB", "TEXT");

    /** Q3 新增：Wave8 REWRITE_MAP 未含 BOOLEAN，按 SQL-T08 定为 tinyint(1) */
    private static final String BOOLEAN_TARGET = "TINYINT(1)";

    private static final String BARE_NUMBER_TARGET =
            "DECIMAL(" + NUMBER_P + "," + NUMBER_S + ")";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final Pattern P_NVARCHAR2_LEN =
            Pattern.compile("\\bNVARCHAR2\\s*\\(\\s*(\\d+)\\s*\\)", FLAGS);
    private static final Pattern P_VARCHAR2_LEN =
            Pattern.compile("\\bVARCHAR2\\s*\\(\\s*(\\d+)\\s*\\)", FLAGS);
    private static final Pattern P_NVARCHAR2_BARE =
            Pattern.compile("\\bNVARCHAR2\\b(?!\\s*\\()", FLAGS);
    private static final Pattern P_VARCHAR2_BARE =
            Pattern.compile("\\bVARCHAR2\\b(?!\\s*\\()", FLAGS);
    private static final Pattern P_NUMBER_PS =
            Pattern.compile("\\bNUMBER\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)", FLAGS);
    private static final Pattern P_NUMBER_P =
            Pattern.compile("\\bNUMBER\\s*\\(\\s*(\\d+)\\s*\\)", FLAGS);
    private static final Pattern P_NUMBER_BARE =
            Pattern.compile("\\bNUMBER\\b(?!\\s*\\()", FLAGS);
    private static final Pattern P_CLOB = Pattern.compile("\\bCLOB\\b", FLAGS);
    private static final Pattern P_BOOLEAN = Pattern.compile("\\bBOOLEAN\\b", FLAGS);

    /** 命中类型描述（label / 规则元素 / 命中形态），用于 DdlDraft.mappedTypes */
    private static final List<TypeSpec> HIT_SPECS = new ArrayList<>();

    /** error 级类型元素（只读取自 allTypeRules()，用于「不生成草稿」判定） */
    private static final List<String> ERROR_ELEMENTS = new ArrayList<>();

    static {
        HIT_SPECS.add(new TypeSpec("VARCHAR2", "varchar2", P_VARCHAR2_LEN, P_VARCHAR2_BARE));
        HIT_SPECS.add(new TypeSpec("NVARCHAR2", "nvarchar2", P_NVARCHAR2_LEN, P_NVARCHAR2_BARE));
        HIT_SPECS.add(new TypeSpec("NUMBER", "number", P_NUMBER_PS, P_NUMBER_P, P_NUMBER_BARE));
        HIT_SPECS.add(new TypeSpec("CLOB", "clob", P_CLOB));
        HIT_SPECS.add(new TypeSpec("BOOLEAN", "boolean", P_BOOLEAN));
        for (SqlCompatibilityRules.Rule rule : SqlCompatibilityRules.allTypeRules()) {
            if (SqlCompatibilityIssue.LEVEL_ERROR.equals(rule.level)
                    && "type".equals(rule.category)) {
                ERROR_ELEMENTS.add(rule.element.toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * 按 Q3 映射表改写 DDL 文本（仅 warn 级类型生效；error 级类型不改写）。
     *
     * @param ddl 原始 DDL
     * @return 映射后 DDL；无命中时原样返回
     */
    public String map(String ddl) {
        if (ddl == null || ddl.isEmpty()) {
            return ddl;
        }
        String result = ddl;
        // 先 NVARCHAR2 再 VARCHAR2，避免 VARCHAR2 命中 NVARCHAR2 内部子串
        result = apply(result, "nvarchar2", P_NVARCHAR2_LEN, VARCHAR_PREFIX + "$1)");
        result = apply(result, "varchar2", P_VARCHAR2_LEN, VARCHAR_PREFIX + "$1)");
        result = apply(result, "nvarchar2", P_NVARCHAR2_BARE,
                VARCHAR_PREFIX + DEFAULT_VARCHAR_LEN + ")");
        result = apply(result, "varchar2", P_VARCHAR2_BARE,
                VARCHAR_PREFIX + DEFAULT_VARCHAR_LEN + ")");
        // NUMBER 先带精度、再单精度、最后裸类型，避免顺序导致误替换
        result = apply(result, "number", P_NUMBER_PS, NUMBER_PREFIX + "$1,$2)");
        result = apply(result, "number", P_NUMBER_P, NUMBER_PREFIX + "$1,0)");
        result = apply(result, "number", P_NUMBER_BARE, BARE_NUMBER_TARGET);
        result = apply(result, "clob", P_CLOB, CLOB_TARGET);
        result = apply(result, "boolean", P_BOOLEAN, BOOLEAN_TARGET);
        return result;
    }

    /**
     * 是否命中 error 级类型（目标无等价语义，如 ROWID/UROWID）。
     * 级别只读取自 {@code SqlCompatibilityRules.allTypeRules()}。
     */
    public boolean hasErrorLevel(String ddl) {
        if (ddl == null || ddl.isEmpty()) {
            return false;
        }
        String lower = ddl.toLowerCase(Locale.ROOT);
        for (String element : ERROR_ELEMENTS) {
            if (Pattern.compile("\\b" + Pattern.quote(element) + "\\b").matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 命中的 warn 级类型名列表（按固定顺序去重）。
     */
    public List<String> matchedTypes(String ddl) {
        List<String> matched = new ArrayList<>();
        if (ddl == null || ddl.isEmpty()) {
            return matched;
        }
        for (TypeSpec spec : HIT_SPECS) {
            if (!SqlCompatibilityIssue.LEVEL_WARN.equals(levelOf(spec.element))) {
                continue;
            }
            for (Pattern pattern : spec.patterns) {
                if (pattern.matcher(ddl).find()) {
                    if (!matched.contains(spec.label)) {
                        matched.add(spec.label);
                    }
                    break;
                }
            }
        }
        return matched;
    }

    /** 按规则表取类型元素级别；未登记的类型返回 null（不改写） */
    private String levelOf(String element) {
        for (SqlCompatibilityRules.Rule rule : SqlCompatibilityRules.allTypeRules()) {
            if (rule.element.equalsIgnoreCase(element)) {
                return rule.level;
            }
        }
        return null;
    }

    /** 仅 warn 级规则生成改写；error / info 级原样返回 */
    private String apply(String sql, String element, Pattern pattern, String replacement) {
        if (!SqlCompatibilityIssue.LEVEL_WARN.equals(levelOf(element))) {
            return sql;
        }
        Matcher matcher = pattern.matcher(sql);
        try {
            return matcher.replaceAll(replacement);
        } catch (RuntimeException e) {
            return sql;
        }
    }

    /** 只读暴露 error 级类型元素，便于诊断与 UI 提示 */
    public List<String> errorTypeElements() {
        return Collections.unmodifiableList(ERROR_ELEMENTS);
    }

    private static final class TypeSpec {
        private final String label;
        private final String element;
        private final Pattern[] patterns;

        TypeSpec(String label, String element, Pattern... patterns) {
            this.label = label;
            this.element = element;
            this.patterns = patterns;
        }
    }
}
