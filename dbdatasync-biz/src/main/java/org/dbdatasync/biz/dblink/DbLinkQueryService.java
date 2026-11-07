package org.dbdatasync.biz.dblink;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DBLink 查询服务（方向五「多技术路线补充」之 DBLink 读取通道）。
 * <p>
 * 能力：
 * 1. {@link #listDbLinks()}——探测源端 {@code all_db_links} 视图，列出可用 DBLink；
 * 2. {@link #query(String, int)}——执行含 {@code @dblink} 后缀的只读 SELECT 查询，
 *    自动从 SQL 中识别链接名，返回列名 + 字符串化数据行 + 耗时。
 * 安全边界（保守口径）：仅放行 SELECT / WITH 开头的只读语句；不建立真实连接，
 * 连接经 {@link ConnectionProvider} 注入；行数受 maxRows 上限约束。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class DbLinkQueryService {

    /** 默认最大返回行数。 */
    public static final int MAX_ROWS_DEFAULT = 500;

    /** @dblink 链接名识别正则：@ 后跟 Oracle 常规标识符字符。 */
    private static final Pattern P_DBLINK = Pattern.compile("@([A-Za-z_][A-Za-z0-9_$#]*)");

    /** all_db_links 探测 SQL。 */
    private static final String SQL_LIST_DB_LINKS =
            "SELECT owner, db_link, username, host FROM all_db_links ORDER BY db_link";

    /** 连接提供者。 */
    private final ConnectionProvider provider;

    /**
     * 构造 DBLink 查询服务。
     *
     * @param provider 连接提供者，不可为 null
     */
    public DbLinkQueryService(ConnectionProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        this.provider = provider;
    }

    /**
     * 探测源端可用的 DBLink 列表（查询 all_db_links 视图）。
     * <p>非 Oracle 源端或视图不可访问时抛出 SQLException，由调用方决定降级策略。</p>
     *
     * @return DBLink 元数据列表（可能为空）
     * @throws SQLException 查询失败时抛出
     */
    public List<DbLinkInfo> listDbLinks() throws SQLException {
        Connection conn = provider.get();
        Statement st = null;
        ResultSet rs = null;
        try {
            st = conn.createStatement();
            rs = st.executeQuery(SQL_LIST_DB_LINKS);
            List<DbLinkInfo> result = new ArrayList<DbLinkInfo>();
            while (rs.next()) {
                result.add(new DbLinkInfo(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4)));
            }
            return result;
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
        }
    }

    /**
     * 执行只读（SELECT / WITH 开头）查询，支持 {@code @dblink} 后缀。
     *
     * @param sql     待执行 SQL（必须只读）
     * @param maxRows 最大返回行数，&lt;= 0 时按默认 500
     * @return 查询结果（列名 + 字符串化行 + 耗时 + 链接名）
     * @throws SQLException 执行失败时抛出；SQL 非只读时抛 IllegalArgumentException
     */
    public DbLinkQueryResult query(String sql, int maxRows) throws SQLException {
        if (sql == null || sql.trim().length() == 0) {
            throw new IllegalArgumentException("sql must not be empty");
        }
        if (!isReadOnlySql(sql)) {
            throw new IllegalArgumentException("仅放行只读查询（SELECT/WITH 开头）: " + preview(sql));
        }
        int limit = maxRows > 0 ? maxRows : MAX_ROWS_DEFAULT;
        Connection conn = provider.get();
        Statement st = null;
        ResultSet rs = null;
        long start = System.nanoTime();
        try {
            st = conn.createStatement();
            st.setMaxRows(limit);
            rs = st.executeQuery(sql);
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<String> columns = new ArrayList<String>(colCount);
            for (int i = 1; i <= colCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }
            List<List<String>> rows = new ArrayList<List<String>>();
            boolean truncated = false;
            while (rs.next()) {
                if (rows.size() >= limit) {
                    // 已达上限且仍有数据：标记截断
                    truncated = true;
                    break;
                }
                List<String> row = new ArrayList<String>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    Object v = rs.getObject(i);
                    row.add(v == null ? null : String.valueOf(v));
                }
                rows.add(row);
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            return new DbLinkQueryResult(sql, extractDbLinkName(sql), columns, rows, truncated, elapsed);
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
        }
    }

    /**
     * 判断 SQL 是否为只读查询：去除前导注释与空白后以 SELECT 或 WITH 开头。
     *
     * @param sql 待判断 SQL
     * @return 只读返回 true
     */
    public static boolean isReadOnlySql(String sql) {
        if (sql == null) {
            return false;
        }
        String head = stripLeadingComments(sql).trim().toUpperCase();
        return head.startsWith("SELECT") || head.startsWith("WITH");
    }

    /**
     * 从 SQL 中提取第一个 @dblink 链接名。
     *
     * @param sql SQL 文本
     * @return 链接名；无则返回 null
     */
    public static String extractDbLinkName(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher m = P_DBLINK.matcher(sql);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * 去除 SQL 前导注释（-- 行注释与 块注释）。
     *
     * @param sql 原始 SQL
     * @return 去除前导注释后的 SQL
     */
    static String stripLeadingComments(String sql) {
        String s = sql.trim();
        while (true) {
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                if (nl < 0) {
                    return "";
                }
                s = s.substring(nl + 1).trim();
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                if (end < 0) {
                    return "";
                }
                s = s.substring(end + 2).trim();
            } else {
                return s;
            }
        }
    }

    /**
     * 生成用于报错的 SQL 预览（截断到 80 字符）。
     *
     * @param sql 原始 SQL
     * @return 预览串
     */
    private static String preview(String sql) {
        return sql.length() <= 80 ? sql : sql.substring(0, 80) + "...";
    }

    /**
     * 静默关闭 ResultSet。
     *
     * @param rs 结果集
     */
    private static void closeQuietly(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException ignore) {
                // 静默忽略
            }
        }
    }

    /**
     * 静默关闭 Statement。
     *
     * @param st 语句
     */
    private static void closeQuietly(Statement st) {
        if (st != null) {
            try {
                st.close();
            } catch (SQLException ignore) {
                // 静默忽略
            }
        }
    }
}
