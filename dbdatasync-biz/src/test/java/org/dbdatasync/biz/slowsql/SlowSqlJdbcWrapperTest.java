package org.dbdatasync.biz.slowsql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * {@link SlowSqlJdbcWrapper} 单元测试：底层 Connection / Statement 均为
 * JDK 动态代理 mock（可编程睡眠与返回值），不连接任何真实数据库。
 */
public class SlowSqlJdbcWrapperTest {

    /** 测试用采集器：线程安全收集记录。 */
    private static class ListCollector implements SlowSqlCollector {
        final List<SlowSqlRecord> records = new ArrayList<SlowSqlRecord>();

        @Override
        public synchronized void collect(SlowSqlRecord record) {
            records.add(record);
        }

        @Override
        public synchronized List<SlowSqlRecord> snapshot() {
            return new ArrayList<SlowSqlRecord>(records);
        }
    }

    /**
     * 构造 mock Connection：prepareStatement/createStatement 返回同时实现
     * Statement 与 PreparedStatement 接口的代理。
     */
    private static Connection mockConnection(final InvocationHandler stmtHandler) {
        return (Connection) Proxy.newProxyInstance(SlowSqlJdbcWrapperTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("createStatement".equals(name) || "prepareStatement".equals(name)) {
                            return Proxy.newProxyInstance(SlowSqlJdbcWrapperTest.class.getClassLoader(),
                                    new Class<?>[]{Statement.class, java.sql.PreparedStatement.class}, stmtHandler);
                        }
                        if ("getMetaData".equals(name)) {
                            return null;
                        }
                        return null;
                    }
                });
    }

    /**
     * 可编程 handler：执行类方法睡眠 sleepMillis 后按方法语义返回——
     * execute 返回 TRUE，executeQuery 返回 null（不触碰 ResultSet），
     * executeUpdate / executeBatch 返回 cannedResult。
     */
    private static InvocationHandler sleepingHandler(final long sleepMillis, final Object cannedResult,
                                                     final SQLException throwOnExecute) {
        return new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (throwOnExecute != null
                        && ("execute".equals(name) || "executeQuery".equals(name) || "executeUpdate".equals(name))) {
                    throw throwOnExecute;
                }
                if ("execute".equals(name)) {
                    if (sleepMillis > 0) {
                        Thread.sleep(sleepMillis);
                    }
                    return Boolean.TRUE;
                }
                if ("executeQuery".equals(name)) {
                    if (sleepMillis > 0) {
                        Thread.sleep(sleepMillis);
                    }
                    return null;
                }
                if ("executeUpdate".equals(name) || "executeBatch".equals(name)) {
                    if (sleepMillis > 0) {
                        Thread.sleep(sleepMillis);
                    }
                    return cannedResult;
                }
                if ("close".equals(name)) {
                    return null;
                }
                return null;
            }
        };
    }

    @Test
    public void fastExecutionNotCollected() throws Exception {
        ListCollector collector = new ListCollector();
        Connection mock = mockConnection(sleepingHandler(1, 0, null));
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, collector, "task-fast", 5000);
        Statement stmt = wrapper.createStatement();
        stmt.execute("SELECT 1");
        assertEquals(0, collector.records.size());
    }

    @Test
    public void slowStatementExecuteCollected() throws Exception {
        ListCollector collector = new ListCollector();
        Connection mock = mockConnection(sleepingHandler(40, 0, null));
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, collector, "task-a", 10);
        Statement stmt = wrapper.createStatement();
        stmt.execute("UPDATE t SET c = 1");
        assertEquals(1, collector.records.size());
        SlowSqlRecord r = collector.records.get(0);
        assertEquals("UPDATE t SET c = 1", r.getSql());
        assertEquals("task-a", r.getSource());
        assertTrue("耗时 " + r.getExecuteMillis() + " 应 >= 10", r.getExecuteMillis() >= 10);
        assertTrue(r.isThresholdExceeded());
        assertTrue(r.getCapturedAt() > 0);
    }

    @Test
    public void preparedStatementSqlBoundAtCreation() throws Exception {
        ListCollector collector = new ListCollector();
        Connection mock = mockConnection(sleepingHandler(40, 0, null));
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, collector, "task-b", 10);
        java.sql.PreparedStatement ps = wrapper.prepareStatement("INSERT INTO t(a) VALUES (?)");
        ps.executeUpdate();
        assertEquals(1, collector.records.size());
        assertEquals("INSERT INTO t(a) VALUES (?)", collector.records.get(0).getSql());
        assertEquals(0, collector.records.get(0).getRows());
    }

    @Test
    public void executeUpdateRowsCaptured() throws Exception {
        ListCollector collector = new ListCollector();
        Connection mock = mockConnection(sleepingHandler(40, 123, null));
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, collector, "task-c", 10);
        Statement stmt = wrapper.createStatement();
        stmt.executeUpdate("DELETE FROM t WHERE id < 100");
        assertEquals(1, collector.records.size());
        assertEquals(123, collector.records.get(0).getRows());
    }

    @Test
    public void executeBatchRowsSummed() throws Exception {
        ListCollector collector = new ListCollector();
        Connection mock = mockConnection(sleepingHandler(40, new int[]{3, -1, 5, 2}, null));
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, collector, "task-d", 10);
        Statement stmt = wrapper.createStatement();
        stmt.executeBatch();
        assertEquals(1, collector.records.size());
        // 负数（成功但未知行数）不计入，正数求和 3+5+2=10
        assertEquals(10, collector.records.get(0).getRows());
    }

    @Test
    public void executeExceptionRethrownAndNotCollected() throws Exception {
        ListCollector collector = new ListCollector();
        SQLException boom = new SQLException("boom");
        Connection mock = mockConnection(sleepingHandler(40, 0, boom));
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, collector, "task-e", 10);
        Statement stmt = wrapper.createStatement();
        try {
            stmt.execute("SELECT 1");
            fail("应抛出 SQLException");
        } catch (SQLException e) {
            assertEquals("boom", e.getMessage());
        }
        assertEquals(0, collector.records.size());
    }

    @Test
    public void proxyConnectionRoutesAndPassesThrough() throws Exception {
        ListCollector collector = new ListCollector();
        Connection mock = mockConnection(sleepingHandler(40, 0, null));
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, collector, "task-f", 10);
        Connection proxied = wrapper.proxyConnection();
        // prepareStatement 经代理走计时路径
        java.sql.PreparedStatement ps = proxied.prepareStatement("SELECT * FROM t");
        ps.executeUpdate();
        assertEquals(1, collector.records.size());
        // 透传方法（mock 返回 null）
        assertNull(proxied.getMetaData());
        // 代理对象应实现 Connection 接口
        assertTrue(proxied instanceof Connection);
    }

    @Test
    public void slowSqlThresholdConfigAccepted() throws Exception {
        ListCollector collector = new ListCollector();
        Connection mock = mockConnection(sleepingHandler(40, 0, null));
        SlowSqlThreshold threshold = new SlowSqlThreshold();
        threshold.setThresholdMillis(20);
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, collector, "task-g", threshold);
        wrapper.createStatement().execute("SELECT 1");
        assertEquals(1, collector.records.size());
        assertTrue(collector.records.get(0).getExecuteMillis() >= 20);
    }

    @Test
    public void zeroThresholdFallsBackToDefault() throws Exception {
        ListCollector collector = new ListCollector();
        Connection mock = mockConnection(sleepingHandler(1, 0, null));
        // thresholdMillis <=0 回退默认 1000ms，睡眠 1ms 不应采集
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, collector, "task-h", 0);
        wrapper.createStatement().execute("SELECT 1");
        assertEquals(0, collector.records.size());
    }

    @Test
    public void unwrapReturnsOriginalDelegate() throws Exception {
        Connection mock = mockConnection(sleepingHandler(0, 0, null));
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, new ListCollector(), "src", 10);
        assertTrue(mock == wrapper.unwrapDelegate());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullDelegateRejected() {
        new SlowSqlJdbcWrapper(null, new ListCollector(), "src", 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullCollectorRejected() {
        Connection mock = mockConnection(sleepingHandler(0, 0, null));
        new SlowSqlJdbcWrapper(mock, null, "src", 10);
    }

    @Test
    public void inferRowsSemantics() {
        assertEquals(0, SlowSqlJdbcWrapper.inferRows(null));
        assertEquals(7, SlowSqlJdbcWrapper.inferRows(Integer.valueOf(7)));
        assertEquals(9, SlowSqlJdbcWrapper.inferRows(Long.valueOf(9)));
        assertEquals(10, SlowSqlJdbcWrapper.inferRows(new int[]{10, -1, -2}));
        assertEquals(0, SlowSqlJdbcWrapper.inferRows(new Object()));
    }

    @Test
    public void passthroughMethodsNotTimed() throws Exception {
        ListCollector collector = new ListCollector();
        Connection mock = mockConnection(sleepingHandler(0, 0, null));
        SlowSqlJdbcWrapper wrapper = new SlowSqlJdbcWrapper(mock, collector, "task-i", 0);
        Statement stmt = wrapper.createStatement();
        // 阈值为 0 时任何"计时方法"都会采集；close 等非计时方法不应触发采集
        stmt.close();
        assertEquals(0, collector.records.size());
    }
}
