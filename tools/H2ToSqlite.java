import java.sql.*;
import java.util.*;

/**
 * 一次性元库迁移：H2 (./data/DBDataSync.mv.db) -> SQLite (./data/DBDataSync.db)。
 *
 * <p>覆盖所有 DBDataSync_ 前缀表，包含运行期动态创建的 task_detail 分表与
 * runtime_ledger 运行态账本；按 JDBC 类型映射到 SQLite 亲和类型，逐表批量复制。
 * 必须在平台停服后运行（H2 文件库单进程独占）。可重复执行（INSERT OR REPLACE）。</p>
 */
public class H2ToSqlite {

    public static void main(String[] args) throws Exception {
        String h2Path = args.length > 0 ? args[0] : "./data/DBDataSync";
        String sqlitePath = args.length > 1 ? args[1] : "./data/DBDataSync.db";

        java.io.File sf = new java.io.File(sqlitePath).getAbsoluteFile();
        if (sf.getParentFile() != null) {
            sf.getParentFile().mkdirs();
        }

        Class.forName("org.h2.Driver");
        Class.forName("org.sqlite.JDBC");
        // ACCESS_MODE_DATA=r：只读打开，绝不改动现网 H2 文件
        Connection src = DriverManager.getConnection(
                "jdbc:h2:file:" + h2Path + ";MODE=MySQL;ACCESS_MODE_DATA=r", "sa", "");
        Connection dst = DriverManager.getConnection(
                "jdbc:sqlite:" + sqlitePath + "?busy_timeout=15000&journal_mode=WAL&synchronous=NORMAL");
        dst.setAutoCommit(false);

        List<String> tables = new ArrayList<>();
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC' "
                             + "AND UPPER(TABLE_NAME) LIKE 'DBDATASYNC\\_%' ESCAPE '\\' "
                             + "ORDER BY TABLE_NAME")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        System.out.println("H2 tables found: " + tables.size());

        long total = 0;
        for (String table : tables) {
            long n = copyTable(src, dst, table);
            total += n;
            System.out.printf("  %-58s %6d rows%n", table, n);
        }
        dst.commit();

        // 逐表行数对账，数字不一致直接失败
        int mismatch = 0;
        for (String table : tables) {
            long a = count(src, table);
            long b = count(dst, table);
            if (a != b) {
                System.err.printf("MISMATCH %s: h2=%d sqlite=%d%n", table, a, b);
                mismatch++;
            }
        }
        System.out.println("TOTAL ROWS = " + total + "; MISMATCH = " + mismatch);
        src.close();
        dst.close();
        if (mismatch > 0) {
            System.exit(2);
        }
    }

    private static long copyTable(Connection src, Connection dst, String table) throws SQLException {
        String q = "\"" + table.replace("\"", "\"\"") + "\"";
        long rows = 0;
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + q)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(q).append(" (");
            StringBuilder names = new StringBuilder();
            StringBuilder marks = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    ddl.append(", ");
                    names.append(",");
                    marks.append(",");
                }
                String col = md.getColumnName(i);
                ddl.append("\"").append(col.replace("\"", "\"\"")).append("\" ")
                        .append(sqliteType(md.getColumnType(i)));
                names.append("\"").append(col.replace("\"", "\"\"")).append("\"");
                marks.append("?");
            }
            if (hasIdColumn(md, cols)) {
                ddl.append(", PRIMARY KEY (\"ID\")");
            }
            ddl.append(")");
            try (Statement ds = dst.createStatement()) {
                ds.execute(ddl.toString());
            }
            String insert = "INSERT OR REPLACE INTO " + q + " (" + names + ") VALUES (" + marks + ")";
            try (PreparedStatement ps = dst.prepareStatement(insert)) {
                while (rs.next()) {
                    for (int i = 1; i <= cols; i++) {
                        bind(ps, rs, i, md.getColumnType(i));
                    }
                    ps.addBatch();
                    rows++;
                    if (rows % 500 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
        }
        return rows;
    }

    private static void bind(PreparedStatement ps, ResultSet rs, int i, int type) throws SQLException {
        if (type == Types.CLOB || type == Types.NCLOB || type == Types.LONGVARCHAR) {
            ps.setString(i, rs.getString(i));
        } else if (type == Types.BLOB || type == Types.BINARY
                || type == Types.VARBINARY || type == Types.LONGVARBINARY) {
            ps.setBytes(i, rs.getBytes(i));
        } else {
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Clob) {
                java.sql.Clob c = (java.sql.Clob) v;
                ps.setString(i, c.getSubString(1, (int) c.length()));
            } else if (v instanceof java.sql.Blob) {
                java.sql.Blob b = (java.sql.Blob) v;
                ps.setBytes(i, b.getBytes(1, (int) b.length()));
            } else {
                ps.setObject(i, v);
            }
        }
    }

    private static boolean hasIdColumn(ResultSetMetaData md, int cols) throws SQLException {
        for (int i = 1; i <= cols; i++) {
            if ("ID".equalsIgnoreCase(md.getColumnName(i))) {
                return true;
            }
        }
        return false;
    }

    private static String sqliteType(int sqlType) {
        switch (sqlType) {
            case Types.BIGINT:
            case Types.INTEGER:
            case Types.SMALLINT:
            case Types.TINYINT:
            case Types.BOOLEAN:
            case Types.BIT:
                return "INTEGER";
            case Types.NUMERIC:
            case Types.DECIMAL:
            case Types.DOUBLE:
            case Types.FLOAT:
            case Types.REAL:
                return "NUMERIC";
            case Types.BLOB:
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return "BLOB";
            default:
                return "TEXT";
        }
    }

    private static long count(Connection c, String table) throws SQLException {
        String q = "\"" + table.replace("\"", "\"\"") + "\"";
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(1) FROM " + q)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
