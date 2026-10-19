/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.common.util;

import java.util.regex.Pattern;

/**
 * SQL 注入防护工具。
 *
 * <p>所有由配置/用户输入驱动的数据库标识符（表名、列名、schema、catalog）在拼入 SQL 前，
 * 必须经过 {@link #validateIdentifier(String)} 白名单校验，防止通过表名/列名注入恶意 SQL。</p>
 *
 * <p>自定义 SQL 条件（{@link org.dbdatasync.sdk.enums.OperationEnum#SQL}）属于高权限能力，
 * 必须经过 {@link #validateCustomSql(String)} 危险关键字黑名单校验，仅允许受限的查询片段。</p>
 *
 * <p>增量监听系统函数表达式（形如 {@code $select max(update_time)$}）内部为直接拼接的 SQL 片段，
 * 必须经过 {@link #validateSystemFunction(String)} 白名单校验，仅允许 {@code select 函数(标识符)} 形式。</p>
 *
 * <p>自定义查询表 SQL（{@code CUSTOM_TABLE_SQL}）必须经过 {@link #validateReadonlySql(String)}
 * 只读校验，仅允许以 SELECT/WITH 开头的单条查询语句。</p>
 *
 * @author DBDataSync Security
 */
public final class SqlIdentifierValidator {

    /**
     * 危险关键字黑名单（词边界匹配，避免误伤 update_time 这类含关键字的列名）。
     * 禁止任何写操作、系统命令、危险函数等片段。
     */
    private static final Pattern[] DANGEROUS_PATTERNS = {
            // 多语句 / 注释 / 特殊 token（直接子串）
            Pattern.compile(";"),
            Pattern.compile("--"),
            Pattern.compile("/\\*"),
            Pattern.compile("\\*/"),
            Pattern.compile("#"),
            Pattern.compile("@@"),
            Pattern.compile("0x[0-9a-f]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchar\\s*\\(", Pattern.CASE_INSENSITIVE),
            // 写操作 / 危险语句（词边界）
            Pattern.compile(
                    "\\b(drop|delete|update|insert|truncate|alter|create|replace|exec|execute|grant|revoke|merge|call|union|into|load|outfile|dumpfile|sleep|benchmark|information_schema)\\b",
                    Pattern.CASE_INSENSITIVE),
            // 系统内部 schema 前缀（PostgreSQL / SQL Server 系统对象）
            Pattern.compile("\\b(pg_|xp_)[a-z]+", Pattern.CASE_INSENSITIVE)
    };

    private SqlIdentifierValidator() {
    }

    /**
     * 数据库标识符危险字符集合（凡是正常标识符绝不应包含的注入分隔符）。
     * 含引号、分号、等号、注释符、空格、圆括号闭合、星号通配、百分号、反引号、美元符、反斜杠等。
     */
    private static final Pattern DANGEROUS_IDENTIFIER = Pattern.compile(
            "[\"';=#\\-*/\\\\\\s()`%@<>\\[\\]\\{\\}\\$\\|]");

    /**
     * 系统函数表达式白名单：仅允许 {@code select 函数(标识符)} 形式（可含简单算术组合），
     * 兼容 {@code select max(update_time)}、{@code select count(*)} 等增量监听取值。
     */
    private static final Pattern SYSTEM_FUNCTION_EXPR = Pattern.compile(
            "^select\\s+[a-zA-Z_][\\w.]*\\s*\\(\\s*(\\*|[a-zA-Z_][\\w.]*)\\s*\\)"
                    + "(\\s*[+\\-*/]\\s*[a-zA-Z_][\\w.]*\\s*\\(\\s*(\\*|[a-zA-Z_][\\w.]*)\\s*\\))*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 只读自定义 SQL 开头白名单。
     */
    private static final Pattern READONLY_SQL_START = Pattern.compile(
            "^\\s*(select|with)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 校验数据库标识符（表名/列名/索引名/schema 等）。
     *
     * <p>采用"危险字符硬拒绝"策略：正常标识符（含中文、字母、数字、下划线、点）一律放行，
     * 一旦包含任何 SQL 注入分隔符（引号、分号、等号、注释、空格、括号等）即判定为注入并拒绝。
     * 该策略在兼容国产库中文标识符的同时有效阻断注入。</p>
     *
     * @param identifier 待校验标识符
     * @return 校验通过返回去空白后的原值
     * @throws IllegalArgumentException 标识符含危险字符（疑似注入）
     */
    public static String validateIdentifier(String identifier) {
        if (StringUtil.isBlank(identifier)) {
            throw new IllegalArgumentException("SQL identifier must not be blank");
        }
        String trimmed = identifier.trim();
        if (DANGEROUS_IDENTIFIER.matcher(trimmed).find()) {
            throw new IllegalArgumentException("Illegal SQL identifier (possible injection): " + trimmed);
        }
        return trimmed;
    }

    /**
     * 校验自定义 SQL 条件片段（仅允许安全查询片段）。
     *
     * @param customSql 用户自定义条件
     * @return 校验通过返回原值（去除首尾空白）
     * @throws IllegalArgumentException 含危险关键字或多语句
     */
    public static String validateCustomSql(String customSql) {
        if (StringUtil.isBlank(customSql)) {
            return StringUtil.EMPTY;
        }
        String normalized = customSql.trim();
        String lower = normalized.toLowerCase();
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(lower).find()) {
                throw new IllegalArgumentException(
                        "Illegal custom SQL condition contains forbidden token '" + pattern.pattern() + "': " + normalized);
            }
        }
        // 禁止字符串中的单引号闭合后拼接（简单但有效的二次防护）
        if (normalized.split("'", -1).length % 2 == 0) {
            throw new IllegalArgumentException("Illegal custom SQL condition (unbalanced quotes): " + normalized);
        }
        return normalized;
    }

    /**
     * 校验增量监听系统函数表达式（{@code $select max(update_time)$} 的内部片段）。
     *
     * <p>该类表达式由用户配置并直接拼入 SQL，必须白名单校验：
     * 仅允许 {@code select 函数(标识符)} 形式的只读取值表达式，阻断拼接任意 SQL 的注入。</p>
     *
     * @param expr 表达式内部内容（不含首尾 {@code $}）
     * @return 校验通过返回去空白后的表达式
     * @throws IllegalArgumentException 表达式不符合白名单
     */
    public static String validateSystemFunction(String expr) {
        if (StringUtil.isBlank(expr)) {
            throw new IllegalArgumentException("Illegal system function expression (blank): $..$");
        }
        String trimmed = expr.trim();
        if (!SYSTEM_FUNCTION_EXPR.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Illegal system function expression, only 'select fn(identifier)' allowed: $" + trimmed + "$");
        }
        return trimmed;
    }

    /**
     * 校验自定义查询表 SQL（只读）。
     *
     * <p>仅允许以 SELECT/WITH 开头的单条只读查询：禁止注释、禁止多语句，
     * 从源头阻断自定义查询被用于执行写操作或堆叠注入。</p>
     *
     * @param sql 自定义查询 SQL
     * @return 校验通过返回去除首尾空白后的 SQL
     * @throws IllegalArgumentException SQL 非只读查询或含注释/多语句
     */
    public static String validateReadonlySql(String sql) {
        if (StringUtil.isBlank(sql)) {
            throw new IllegalArgumentException("Custom SQL must not be blank");
        }
        String trimmed = sql.trim();
        if (!READONLY_SQL_START.matcher(trimmed).find()) {
            throw new IllegalArgumentException(
                    "Illegal custom SQL: must start with SELECT/WITH (read-only): " + trimmed);
        }
        if (trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("*/") || trimmed.contains("#")) {
            throw new IllegalArgumentException("Illegal custom SQL (contains comment): " + trimmed);
        }
        if (trimmed.replaceAll(";\\s*$", "").contains(";")) {
            throw new IllegalArgumentException("Illegal custom SQL (multiple statements): " + trimmed);
        }
        return trimmed;
    }
}
