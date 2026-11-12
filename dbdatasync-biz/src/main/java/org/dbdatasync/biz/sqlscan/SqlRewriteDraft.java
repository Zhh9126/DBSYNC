package org.dbdatasync.biz.sqlscan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 改写草稿生成器（方言兼容扫描的「转换辅助」）。
 *
 * <p>仅对可机械映射的元素做大小写不敏感的字符串替换，输出改写草稿供人工确认；
 * 复杂项（DECODE→CASE、CONNECT BY→递归 CTE、LISTAGG→GROUP_CONCAT 等）不做自动改写
 * （suggestion 示意 + 人工确认），沿用 Wave5「评估不误导」的保守定位。</p>
 *
 * <p>替换按规则顺序执行，同一条 SQL 可命中多条（如 SYSDATE + NVL 同时替换）。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
public final class SqlRewriteDraft {

    /** 函数/关键字级替换（从 → 到，正则安全） */
    private static final Map<String, String> REWRITE_MAP = new LinkedHashMap<>();

    static {
        // Oracle 系 → MySQL 系
        REWRITE_MAP.put("NVL(", "COALESCE(");
        REWRITE_MAP.put("NVL2(", "CASE WHEN ");
        REWRITE_MAP.put("SYSDATE", "NOW()");
        REWRITE_MAP.put("TO_CHAR(", "DATE_FORMAT(");
        REWRITE_MAP.put("TO_DATE(", "STR_TO_DATE(");
        REWRITE_MAP.put("TO_NUMBER(", "CAST(");
        REWRITE_MAP.put("ADD_MONTHS(", "DATE_ADD(");
        // MySQL 系 → Oracle 系（较少）
        REWRITE_MAP.put("IFNULL(", "NVL(");
        // MySQL → PG
        REWRITE_MAP.put("IFNULL(", "COALESCE(");
        REWRITE_MAP.put("DATEDIFF(", "(");
        // PG → MySQL
        REWRITE_MAP.put("::timestamp", "");
        REWRITE_MAP.put("ILIKE", "LIKE");
        REWRITE_MAP.put("STRING_AGG(", "GROUP_CONCAT(");
        // 类型
        REWRITE_MAP.put("VARCHAR2(", "VARCHAR(");
        REWRITE_MAP.put("NVARCHAR2(", "VARCHAR(");
        REWRITE_MAP.put("CLOB", "TEXT");
        REWRITE_MAP.put("NUMBER(", "DECIMAL(");
    }

    private SqlRewriteDraft() {
    }

    /**
     * 生成改写草稿：按映射表顺序逐项替换。
     *
     * @param sql            待改写 SQL（原始语句）
     * @param sourceDialect  源方言（保留，当前不参与匹配差异）
     * @param targetDialect  目标方言（保留，当前不参与匹配差异）
     * @return 改写后 SQL；无可替换项返回原 SQL
     */
    public static String draft(String sql, String sourceDialect, String targetDialect) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String rewritten = sql;
        for (Map.Entry<String, String> e : REWRITE_MAP.entrySet()) {
            rewritten = replaceCaseInsensitive(rewritten, e.getKey(), e.getValue());
        }
        return rewritten;
    }

    /** 大小写不敏感替换（'a.b' 保留原大小写，替换串按映射输出） */
    private static String replaceCaseInsensitive(String src, String from, String to) {
        if (from == null || from.isEmpty()) {
            return src;
        }
        Pattern p = Pattern.compile(Pattern.quote(from), Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(src);
        return m.replaceAll(Matcher.quoteReplacement(to));
    }

    /** 是否产生实际改动 */
    public static boolean changed(String original, String rewritten) {
        return original != null && !original.equals(rewritten);
    }

    /**
     * 只读暴露基础类型/函数替换表（Wave10 P1-1 复用点）。
     *
     * <p>返回 {@link #REWRITE_MAP} 的不可修改视图；{@code REWRITE_MAP} 静态块、
     * {@link #draft(String, String, String)}、{@link #changed(String, String)} 一行不改，
     * 保证 Wave8 5 项改写草稿回归全绿。</p>
     *
     * @return 不可修改的映射表视图
     */
    public static Map<String, String> baseTypeRewriteMap() {
        return Collections.unmodifiableMap(REWRITE_MAP);
    }
}
