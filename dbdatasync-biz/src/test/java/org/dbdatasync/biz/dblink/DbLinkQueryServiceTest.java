package org.dbdatasync.biz.dblink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * {@link DbLinkQueryService} 单元测试：Connection / Statement / ResultSet /
 * ResultSetMetaData 全部为 JDK 动态代理 fake，不连接任何真实数据库。
 */
public class DbLinkQueryServiceTest {

    /** 每次测试的 fake 数据库：记录执行的 SQL 与 setMaxRows 值，按预设返回行。 */
    private static class FakeDb {
        /** 记录 setMaxRows 调用值。 */
        final List<Integer> maxRowsCalls = new ArrayList<Integer>();
        /** 记录 executeQuery 的 SQL。 */
        final List<String> executedSql = new ArrayList<String>();
        /** executeQuery 返回的列名。 */
        String[] columns = {"DB_LINK", "HOST"};
        /** executeQuery 返回的行数据。 */
        List<Object[]> rows = new ArrayList<Object[]>();
        /** createStatement 时抛出的异常（可选）。 */
        SQLException throwOnCreate;

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("createStatement".equals(method.getName())) {
                                if (throwOnCreate != null) {
                                    throw throwOnCreate;
                                }
                                return statement();
                            }
                            return null;
                        }
                    });
        }

        Statement statement() {
            return (Statement) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                    new Class<?>[]{Statement.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            if ("setMaxRows".equals(name)) {
                                maxRowsCalls.add((Integer) args[0]);
                                return null;
                            }
                            if ("executeQuery".equals(name)) {
                                executedSql.add((String) args[0]);
                                return resultSet();
                            }
                            if ("close".equals(name)) {
                                return null;
                            }
                            return null;
                        }
                    });
        }

        ResultSet resultSet() {
            final int[] cursor = {0};
            return (ResultSet) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            if ("next".equals(name)) {
                                return cursor[0]++ < rows.size();
                            }
                            if ("getObject".equals(name) || "getString".equals(name)) {
                                // next() 已把 cursor 前移到当前行 +1，当前行下标为 cursor-1
                                int idx = (Integer) args[0];
                                Object[] row = rows.get(cursor[0] - 1);
                                return row[idx - 1];
                            }
                            if ("wasNull".equals(name)) {
                                return false;
                            }
                            if ("getMetaData".equals(name)) {
                                return metaData();
                            }
                            if ("close".equals(name)) {
                                return null;
                            }
                            return null;
                        }
                    });
        }

        ResultSetMetaData metaData() {
            return (ResultSetMetaData) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                    new Class<?>[]{ResultSetMetaData.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("getColumnCount".equals(name)) {
                                return columns.length;
                            }
                            if ("getColumnLabel".equals(name)) {
                                return columns[(Integer) args[0] - 1];
                            }
                            return null;
                        }
                    });
        }

        ConnectionProvider provider() {
            return new ConnectionProvider() {
                @Override
                public Connection get() throws SQLException {
                    return connection();
                }
            };
        }
    }

    @Test
    public void listDbLinksReturnsRows() throws Exception {
        FakeDb db = new FakeDb();
        db.columns = new String[]{"OWNER", "DB_LINK", "USERNAME", "HOST"};
        db.rows.add(new Object[]{"PUBLIC", "REMOTE.DOMAIN.COM", "APP", "tns_remote"});
        db.rows.add(new Object[]{"PUBLIC", "BACKUP.DOMAIN.COM", "APP", "tns_backup"});
        DbLinkQueryService service = new DbLinkQueryService(db.provider());
        List<DbLinkInfo> links = service.listDbLinks();
        assertEquals(2, links.size());
        assertEquals("REMOTE.DOMAIN.COM", links.get(0).getDbLink());
        assertEquals("tns_backup", links.get(1).getHost());
        assertEquals("PUBLIC", links.get(0).getOwner());
        assertEquals("APP", links.get(0).getUsername());
        // 探测 SQL 确实查询 all_db_links
        assertEquals(1, db.executedSql.size());
        assertTrue(db.executedSql.get(0).contains("all_db_links"));
    }

    @Test
    public void listDbLinksEmptyWhenViewEmpty() throws Exception {
        FakeDb db = new FakeDb();
        DbLinkQueryService service = new DbLinkQueryService(db.provider());
        assertTrue(service.listDbLinks().isEmpty());
    }

    @Test
    public void queryReturnsColumnsRowsAndDblinkUsed() throws Exception {
        FakeDb db = new FakeDb();
        db.columns = new String[]{"ID", "NAME"};
        db.rows.add(new Object[]{Integer.valueOf(1), "Alice"});
        db.rows.add(new Object[]{Integer.valueOf(2), null});
        DbLinkQueryService service = new DbLinkQueryService(db.provider());
        DbLinkQueryResult result = service.query("SELECT id, name FROM t@remote", 100);
        assertEquals(Arrays.asList("ID", "NAME"), result.getColumns());
        assertEquals(2, result.getRowCount());
        assertEquals("1", result.getRows().get(0).get(0));
        assertEquals("Alice", result.getRows().get(0).get(1));
        // null 值保持 null
        assertNull(result.getRows().get(1).get(1));
        assertEquals("2", result.getRows().get(1).get(0));
        assertEquals("remote", result.getDblinkUsed());
        assertFalse(result.isTruncated());
        assertTrue(result.getElapsedMillis() >= 0);
        assertEquals("SELECT id, name FROM t@remote", result.getSql());
    }

    @Test
    public void queryWithoutDblinkWorks() throws Exception {
        FakeDb db = new FakeDb();
        db.columns = new String[]{"CNT"};
        db.rows.add(new Object[]{Integer.valueOf(42)});
        DbLinkQueryService service = new DbLinkQueryService(db.provider());
        DbLinkQueryResult result = service.query("SELECT count(*) AS cnt FROM local_t", 0);
        assertEquals(1, result.getRowCount());
        assertEquals("42", result.getRows().get(0).get(0));
        assertNull(result.getDblinkUsed());
        // maxRows<=0 回退默认 500
        assertEquals(Integer.valueOf(DbLinkQueryService.MAX_ROWS_DEFAULT), db.maxRowsCalls.get(0));
    }

    @Test
    public void queryTruncatesAtMaxRows() throws Exception {
        FakeDb db = new FakeDb();
        db.columns = new String[]{"ID"};
        for (int i = 1; i <= 5; i++) {
            db.rows.add(new Object[]{Integer.valueOf(i)});
        }
        DbLinkQueryService service = new DbLinkQueryService(db.provider());
        DbLinkQueryResult result = service.query("SELECT id FROM big_t@remote", 2);
        assertEquals(2, result.getRowCount());
        assertTrue(result.isTruncated());
        assertEquals("1", result.getRows().get(0).get(0));
        assertEquals("2", result.getRows().get(1).get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void queryRejectsNonReadOnly() throws Exception {
        FakeDb db = new FakeDb();
        DbLinkQueryService service = new DbLinkQueryService(db.provider());
        service.query("DELETE FROM t@remote", 10);
    }

    @Test
    public void queryRejectsNonReadOnlyMessages() throws Exception {
        FakeDb db = new FakeDb();
        DbLinkQueryService service = new DbLinkQueryService(db.provider());
        String[] bad = {"UPDATE t SET a=1", "INSERT INTO t VALUES (1)", "DROP TABLE t", "TRUNCATE TABLE t",
                "", "   ", "-- 只有注释"};
        for (String sql : bad) {
            try {
                service.query(sql, 10);
                fail("应拒绝: " + sql);
            } catch (IllegalArgumentException e) {
                // 预期
            }
        }
        // 确认没有任何 SQL 被执行
        assertEquals(0, db.executedSql.size());
    }

    @Test
    public void readOnlyWithLeadingCommentsAllowed() throws Exception {
        FakeDb db = new FakeDb();
        db.columns = new String[]{"X"};
        db.rows.add(new Object[]{"v"});
        DbLinkQueryService service = new DbLinkQueryService(db.provider());
        DbLinkQueryResult r = service.query("-- 查询说明\n/* 块注释 */ SELECT x FROM t@r", 10);
        assertEquals(1, r.getRowCount());
        assertEquals("r", r.getDblinkUsed());
    }

    @Test
    public void listDbLinksPropagatesSQLException() throws Exception {
        FakeDb db = new FakeDb();
        db.throwOnCreate = new SQLException("all_db_links not found");
        DbLinkQueryService service = new DbLinkQueryService(db.provider());
        try {
            service.listDbLinks();
            fail("应抛出 SQLException");
        } catch (SQLException e) {
            assertEquals("all_db_links not found", e.getMessage());
        }
    }

    @Test
    public void providerExceptionPropagates() throws Exception {
        ConnectionProvider failing = new ConnectionProvider() {
            @Override
            public Connection get() throws SQLException {
                throw new SQLException("cannot connect");
            }
        };
        DbLinkQueryService service = new DbLinkQueryService(failing);
        try {
            service.query("SELECT 1 FROM dual", 10);
            fail("应抛出 SQLException");
        } catch (SQLException e) {
            assertEquals("cannot connect", e.getMessage());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullProviderRejected() {
        new DbLinkQueryService(null);
    }

    @Test
    public void extractDbLinkNameSemantics() {
        assertEquals("remote", DbLinkQueryService.extractDbLinkName("SELECT * FROM t@remote"));
        assertEquals("r1", DbLinkQueryService.extractDbLinkName("select * from a@r1, b@r2"));
        assertEquals("scm_link", DbLinkQueryService.extractDbLinkName("SELECT * FROM s.t@scm_link.domain"));
        assertNull(DbLinkQueryService.extractDbLinkName("SELECT * FROM t"));
        assertNull(DbLinkQueryService.extractDbLinkName(null));
    }

    @Test
    public void isReadOnlySqlSemantics() {
        assertTrue(DbLinkQueryService.isReadOnlySql("SELECT 1"));
        assertTrue(DbLinkQueryService.isReadOnlySql("  with x as (select 1 from dual) select * from x"));
        assertTrue(DbLinkQueryService.isReadOnlySql("-- c\nSELECT 1"));
        assertTrue(DbLinkQueryService.isReadOnlySql("/* c */ WITH a AS (SELECT 1 FROM dual) SELECT * FROM a"));
        assertFalse(DbLinkQueryService.isReadOnlySql("DELETE FROM t"));
        assertFalse(DbLinkQueryService.isReadOnlySql(null));
        assertFalse(DbLinkQueryService.isReadOnlySql("-- 仅注释"));
        assertFalse(DbLinkQueryService.isReadOnlySql("/* 未闭合"));
    }

    @Test
    public void stripLeadingCommentsSemantics() {
        assertEquals("SELECT 1", DbLinkQueryService.stripLeadingComments("  SELECT 1"));
        assertEquals("SELECT 1", DbLinkQueryService.stripLeadingComments("-- a\n-- b\nSELECT 1"));
        assertEquals("SELECT 1", DbLinkQueryService.stripLeadingComments("/* x */ SELECT 1"));
        assertEquals("SELECT 1", DbLinkQueryService.stripLeadingComments("/* x */ -- y\n SELECT 1"));
        assertEquals("", DbLinkQueryService.stripLeadingComments("-- 未闭合行注释"));
        assertEquals("", DbLinkQueryService.stripLeadingComments("/* 未闭合块注释"));
    }
}
