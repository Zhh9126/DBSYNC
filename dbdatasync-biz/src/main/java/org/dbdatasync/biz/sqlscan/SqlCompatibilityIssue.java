package org.dbdatasync.biz.sqlscan;

/**
 * SQL 兼容度扫描单条问题项。
 *
 * <p>level：
 * <ul>
 *   <li>error    —— 目标库无等价语义，必须改造（如 Oracle CONNECT BY → MySQL 递归 CTE，需人工重写）</li>
 *   <li>warn     —— 语义等价但写法不同，可改写（如 NVL → COALESCE、ROWNUM 伪列 → LIMIT）</li>
 *   <li>info     —— 仅提示（如使用了 PG 特有类型/方言空值语义差异），可接受</li>
 * </ul>
 * 扫描器输出「问题定位 + 说明 + 改写建议」；对可机械映射的 warn 项额外给出 {@link #rewrittenSql} 改写草稿，
 * error 级不自动改写（草稿为空，需人工确认）。
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
public class SqlCompatibilityIssue {

    public static final String LEVEL_ERROR = "error";
    public static final String LEVEL_WARN = "warn";
    public static final String LEVEL_INFO = "info";

    /** 级别：error / warn / info */
    private String level;

    /** 分类：function / operator / clause / syntax / type / identifier / other */
    private String category;

    /** 问题代号，如 SQL-F01（函数族） */
    private String code;

    /** 命中的元素（函数名/关键字/操作符/类型名） */
    private String element;

    /** 原始 SQL 片段（整条语句，便于定位） */
    private String sql;

    /** 问题说明 */
    private String message;

    /** 改写建议（目标方言写法示意，不保证为可执行 SQL） */
    private String suggestion;

    /** 改写草稿（对可机械映射的 warn 项做逐元素替换后的 SQL；error/复杂项为空需人工） */
    private String rewrittenSql;

    public SqlCompatibilityIssue() {
    }

    public SqlCompatibilityIssue(String level, String category, String code, String element,
                                 String sql, String message, String suggestion) {
        this(level, category, code, element, sql, message, suggestion, null);
    }

    public SqlCompatibilityIssue(String level, String category, String code, String element,
                                 String sql, String message, String suggestion,
                                 String rewrittenSql) {
        this.level = level;
        this.category = category;
        this.code = code;
        this.element = element;
        this.sql = sql;
        this.message = message;
        this.suggestion = suggestion;
        this.rewrittenSql = rewrittenSql;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getElement() {
        return element;
    }

    public void setElement(String element) {
        this.element = element;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public String getRewrittenSql() {
        return rewrittenSql;
    }

    public void setRewrittenSql(String rewrittenSql) {
        this.rewrittenSql = rewrittenSql;
    }
}
