package org.dbdatasync.biz.dblink;

import static org.junit.Assert.assertEquals;
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
import java.util.List;

import org.junit.Test;

/**
 * {@link DbLinkQueryManager} 单元测试：DbLinkConnectionSource 注入 fake，
 * 底层 JDBC 对象为动态代理 fake，不连真库。
 */
public class DbLinkQueryManagerTest {

    /** fake 连接来源：记录 open 调用次数，返回一个返回空结果集的 fake 连接。 */
    private static class FakeSource implements DbLinkConnectionSource {
        int openCount = 0;
        SQLException throwOnOpen;
        final List<String> knownIds = java.util.Arrays.asList("conn-mysql", "conn-oracle");

        @Override
        public Connection open(String connectorId) throws SQLException {
            openCount++;
            if (throwOnOpen != null) {
                throw throwOnOpen;
            }
            if (!knownIds.contains(connectorId)) {
                throw new SQLException("连接器不存在: " + connectorId);
            }
            return emptyResultConnection();
        }
    }

    /** 空结果（单列，0 行）的 fake 连接。 */
    private static Connection emptyResultConnection() {
        ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(
                DbLinkQueryManagerTest.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getColumnCount".equals(method.getName())) {
                            return 1;
                        }
                        if ("getColumnLabel".equals(method.getName())) {
                            return "DB_LINK";
                        }
                        return null;
                    }
                });
        ResultSet rs = (ResultSet) Proxy.newProxyInstance(
                DbLinkQueryManagerTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        // 空结果集：首次 next 即返回 false
                        if ("next".equals(method.getName())) {
                            return Boolean.FALSE;
                        }
                        if ("getMetaData".equals(method.getName())) {
                            return meta;
                        }
                        return null;
                    }
                });
        Statement st = (Statement) Proxy.newProxyInstance(
                DbLinkQueryManagerTest.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
                        if ("executeQuery".equals(method.getName())) {
                            return rs;
                        }
                        return null;
                    }
                });
        return (Connection) Proxy.newProxyInstance(
                DbLinkQueryManagerTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("createStatement".equals(method.getName())) {
                            return st;
                        }
                        return null;
                    }
                });
    }

    private static DbLinkQueryManager manager(FakeSource source) {
        DbLinkQueryManager m = new DbLinkQueryManager();
        setField(m, "connectionSource", source);
        return m;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void listLinksDelegatesAndOpensConnectionPerCall() throws Exception {
        FakeSource source = new FakeSource();
        DbLinkQueryManager m = manager(source);
        assertTrue(m.listLinks("conn-oracle").isEmpty());
        assertEquals(1, source.openCount);
        m.listLinks("conn-mysql");
        assertEquals(2, source.openCount);
    }

    @Test
    public void queryDelegatesAndReturnsResult() throws Exception {
        FakeSource source = new FakeSource();
        DbLinkQueryManager m = manager(source);
        DbLinkQueryResult r = m.query("conn-mysql", "SELECT * FROM t@remote", 10);
        assertEquals(0, r.getRowCount());
        assertEquals("remote", r.getDblinkUsed());
        assertEquals(1, r.getColumns().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void listLinksRejectsEmptyConnectorId() throws Exception {
        manager(new FakeSource()).listLinks("  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void queryRejectsEmptyConnectorId() throws Exception {
        manager(new FakeSource()).query(null, "SELECT 1", 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void queryRejectsEmptySql() throws Exception {
        manager(new FakeSource()).query("conn-mysql", "   ", 10);
    }

    @Test
    public void sourceExceptionPropagates() throws Exception {
        FakeSource source = new FakeSource();
        source.throwOnOpen = new SQLException("连接器不存在: conn-x");
        DbLinkQueryManager m = manager(source);
        try {
            m.listLinks("conn-x");
            fail("应抛出 SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("conn-x"));
        }
    }

    @Test
    public void nonReadOnlyRejectedBeforeOpen() throws Exception {
        FakeSource source = new FakeSource();
        DbLinkQueryManager m = manager(source);
        try {
            m.query("conn-mysql", "DELETE FROM t@remote", 10);
            fail("应拒绝非只读");
        } catch (IllegalArgumentException e) {
            // 预期：只读校验在建连之前，未打开连接
        }
        assertEquals(0, source.openCount);
    }
}
