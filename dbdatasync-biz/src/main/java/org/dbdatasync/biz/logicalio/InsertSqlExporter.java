package org.dbdatasync.biz.logicalio;

import java.io.IOException;
import java.util.List;

/**
 * INSERT SQL 逻辑导出器：将 {@link LogicalRow} 列表渲染为 INSERT 语句文本。
 *
 * <p>方言（仅影响标识符引用方式）：</p>
 * <ul>
 *   <li>{@code mysql}：标识符用反引号，如 {@code `t`}；</li>
 *   <li>{@code oracle}：标识符用双引号且转为大写，如 {@code "T"}；</li>
 *   <li>{@code pg}：标识符用双引号且保持小写，如 {@code "t"}。</li>
 * </ul>
 *
 * <p>批量规则：{@code batchSize > 1} 时合并为多值 INSERT ——
 * mysql / pg 使用 {@code INSERT INTO t (c1,c2) VALUES (...),(...)}；
*  oracle 使用 {@code INSERT ALL ... SELECT 1 FROM DUAL}，每批一条 INSERT ALL。
 * {@code batchSize <= 1} 时每行一条单值 INSERT。</p>
 *
 * <p><b>简化口径说明：</b>所有值统一按 SQL 字符串字面量输出（单引号包裹、内部单引号翻倍转义 {@code ' → ''}），
 * 不区分数值/日期等类型，由调用方按需自行处理类型。</p>
 *
 * <p>本类为纯文本级转换，不涉及 JDBC / 数据库连接。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class InsertSqlExporter {

    /** mysql 方言标识。 */
    public static final String DIALECT_MYSQL = "mysql";
    /** oracle 方言标识。 */
    public static final String DIALECT_ORACLE = "oracle";
    /** pg 方言标识。 */
    public static final String DIALECT_PG = "pg";

    /**
     * 将行数据列表导出为 INSERT 语句文本。
     *
     * @param rows      行数据列表（空列表或 {@code null} 时返回 0，不写出任何内容）
     * @param tableName 目标表名
     * @param dialect   方言："mysql" / "oracle" / "pg"
     * @param batchSize 批大小；&gt;1 时合并为多值 INSERT，否则每行一条
     * @param out       目标输出（StringBuilder / Writer 等 Appendable）
     * @return 生成的 INSERT 语句条数
     * @throws IOException 写出失败时抛出
     * @throws IllegalArgumentException 方言不支持时抛出
     */
    public long export(List<LogicalRow> rows, String tableName, String dialect,
                       int batchSize, Appendable out) throws IOException {
        if (rows == null || rows.isEmpty()) {
            return 0L;
        }
        String dialectKey = dialect == null ? "" : dialect.toLowerCase();
        if (!DIALECT_MYSQL.equals(dialectKey) && !DIALECT_ORACLE.equals(dialectKey)
                && !DIALECT_PG.equals(dialectKey)) {
            throw new IllegalArgumentException("不支持的方言: " + dialect);
        }
        int effectiveBatch = batchSize > 1 ? batchSize : 1;

        long statements = 0L;
        List<String> columns = rows.get(0).getColumns();
        String columnList = buildColumnList(columns, dialectKey);

        for (int start = 0; start < rows.size(); start += effectiveBatch) {
            int end = Math.min(start + effectiveBatch, rows.size());
            statements++;
            if (DIALECT_ORACLE.equals(dialectKey) && effectiveBatch > 1) {
                writeOracleInsertAll(out, rows, start, end, tableName, columnList, dialectKey);
            } else if (effectiveBatch > 1) {
                writeMultiValuesInsert(out, rows, start, end, tableName, columnList);
            } else {
                writeSingleInsert(out, rows.get(start), tableName, columnList);
            }
        }
        return statements;
    }

    /**
     * 写出单行单值 INSERT 语句。
     */
    private void writeSingleInsert(Appendable out, LogicalRow row, String tableName,
                                   String columnList) throws IOException {
        out.append("INSERT INTO ").append(tableName).append(" (").append(columnList)
                .append(") VALUES (").append(buildValueList(row)).append(");\n");
    }

    /**
     * 写出 mysql / pg 风格的多值 INSERT 语句。
     */
    private void writeMultiValuesInsert(Appendable out, List<LogicalRow> rows, int start, int end,
                                        String tableName, String columnList) throws IOException {
        out.append("INSERT INTO ").append(tableName).append(" (").append(columnList).append(") VALUES ");
        for (int i = start; i < end; i++) {
            if (i > start) {
                out.append(",");
            }
            out.append("(").append(buildValueList(rows.get(i))).append(")");
        }
        out.append(";\n");
    }

    /**
     * 写出 oracle 风格的 INSERT ALL 多值语句（每批一条）。
     */
    private void writeOracleInsertAll(Appendable out, List<LogicalRow> rows, int start, int end,
                                      String tableName, String columnList, String dialectKey)
            throws IOException {
        out.append("INSERT ALL\n");
        for (int i = start; i < end; i++) {
            out.append("  INTO ").append(tableName).append(" (").append(columnList)
                    .append(") VALUES (").append(buildValueList(rows.get(i))).append(")\n");
        }
        out.append("SELECT 1 FROM DUAL;\n");
    }

    /**
     * 构建按方言引用的列名清单（如 `c1`,`c2` 或 "C1","C2"）。
     */
    private String buildColumnList(List<String> columns, String dialectKey) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quoteIdentifier(columns.get(i), dialectKey));
        }
        return sb.toString();
    }

    /**
     * 构建一行数据的值清单（统一按字符串字面量输出，简化口径）。
     */
    private String buildValueList(LogicalRow row) {
        List<String> values = row.getValues();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(toSqlStringLiteral(values.get(i)));
        }
        return sb.toString();
    }

    /**
     * 按方言引用标识符：mysql 反引号；oracle 双引号大写；pg 双引号小写。
     */
    private String quoteIdentifier(String identifier, String dialectKey) {
        if (DIALECT_MYSQL.equals(dialectKey)) {
            return '`' + identifier + '`';
        }
        if (DIALECT_ORACLE.equals(dialectKey)) {
            return '"' + identifier.toUpperCase() + '"';
        }
        return '"' + identifier.toLowerCase() + '"';
    }

    /**
     * 将值渲染为 SQL 字符串字面量（单引号包裹，内部单引号翻倍转义）。
     *
     * @param value 原始值（{@code null} 视为空串的字符串字面量）
     * @return SQL 字符串字面量
     */
    private String toSqlStringLiteral(String value) {
        String v = value == null ? "" : value;
        return "'" + v.replace("'", "''") + "'";
    }
}
