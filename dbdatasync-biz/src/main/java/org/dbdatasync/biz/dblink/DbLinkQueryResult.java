package org.dbdatasync.biz.dblink;

import java.util.ArrayList;
import java.util.List;

/**
 * DBLink 查询结果：列名、数据行（字符串化）、耗时与所用链接。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class DbLinkQueryResult {

    /** 执行的 SQL 文本。 */
    private final String sql;

    /** 从 SQL 中识别出的 @dblink 链接名（无则 null）。 */
    private final String dblinkUsed;

    /** 列名列表。 */
    private final List<String> columns;

    /** 数据行（每行为字符串化后的列值，null 值以 null 表示）。 */
    private final List<List<String>> rows;

    /** 实际返回行数。 */
    private final int rowCount;

    /** 是否因达到 maxRows 上限被截断。 */
    private final boolean truncated;

    /** 执行耗时（毫秒）。 */
    private final long elapsedMillis;

    /**
     * 全参构造。
     *
     * @param sql           执行的 SQL 文本
     * @param dblinkUsed    识别出的链接名
     * @param columns       列名列表
     * @param rows          数据行
     * @param truncated     是否被截断
     * @param elapsedMillis 耗时（毫秒）
     */
    public DbLinkQueryResult(String sql, String dblinkUsed, List<String> columns,
                             List<List<String>> rows, boolean truncated, long elapsedMillis) {
        this.sql = sql;
        this.dblinkUsed = dblinkUsed;
        this.columns = columns == null ? new ArrayList<String>() : new ArrayList<String>(columns);
        this.rows = rows == null ? new ArrayList<List<String>>() : rows;
        this.rowCount = this.rows.size();
        this.truncated = truncated;
        this.elapsedMillis = elapsedMillis;
    }

    public String getSql() {
        return sql;
    }

    public String getDblinkUsed() {
        return dblinkUsed;
    }

    public List<String> getColumns() {
        return new ArrayList<String>(columns);
    }

    public List<List<String>> getRows() {
        return rows;
    }

    public int getRowCount() {
        return rowCount;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }
}
