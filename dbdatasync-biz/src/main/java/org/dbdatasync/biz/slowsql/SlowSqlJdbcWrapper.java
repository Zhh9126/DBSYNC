package org.dbdatasync.biz.slowsql;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 慢 SQL JDBC 插桩包装器：以 JDK 动态代理包装 {@link Statement} /
 * {@link PreparedStatement}，对执行类方法计时，超过阈值的执行经
 * {@link SlowSqlCollector} 采集为 {@link SlowSqlRecord}。
 * <p>
 * 用法一（显式包装）：调用 {@link #createStatement()} / {@link #prepareStatement(String)}
 * 获取计时代理后执行 SQL。
 * 用法二（Connection 形态接入）：调用 {@link #proxyConnection()} 获得代理
 * {@link Connection}，其中 createStatement / prepareStatement 返回计时代理，
 * 其余方法透传底层连接——引擎与连接器代码无需改造即可插桩。
 * 本类不建立真实连接，仅包装既有 {@link Connection}；执行异常原样抛出且不采集。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class SlowSqlJdbcWrapper {

    /** Statement / PreparedStatement 上需要计时的执行类方法名集合。 */
    private static final Set<String> TIMED_METHODS = new HashSet<String>(
            Arrays.asList("execute", "executeQuery", "executeUpdate", "executeBatch", "executeLargeBatch"));

    /** 底层真实连接。 */
    private final Connection delegate;

    /** 慢 SQL 采集器。 */
    private final SlowSqlCollector collector;

    /** 来源标识（任务 / 连接器名），写入记录的 source 字段。 */
    private final String source;

    /** 慢 SQL 阈值（毫秒），执行耗时 &gt;= 该值时采集。 */
    private final long thresholdMillis;

    /**
     * 构造插桩包装器。
     *
     * @param delegate        底层真实连接，不可为 null
     * @param collector       慢 SQL 采集器，不可为 null
     * @param source          来源标识（任务 / 连接器名）
     * @param thresholdMillis 慢 SQL 阈值（毫秒），&lt;= 0 时按 {@link SlowSqlThreshold#getThresholdMillis()} 默认值处理
     */
    public SlowSqlJdbcWrapper(Connection delegate, SlowSqlCollector collector, String source, long thresholdMillis) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate connection must not be null");
        }
        if (collector == null) {
            throw new IllegalArgumentException("collector must not be null");
        }
        this.delegate = delegate;
        this.collector = collector;
        this.source = source;
        this.thresholdMillis = thresholdMillis > 0 ? thresholdMillis : new SlowSqlThreshold().getThresholdMillis();
    }

    /**
     * 以 {@link SlowSqlThreshold} 配置构造（阈值取其 thresholdMillis）。
     *
     * @param delegate  底层真实连接
     * @param collector 慢 SQL 采集器
     * @param source    来源标识
     * @param threshold 阈值配置
     */
    public SlowSqlJdbcWrapper(Connection delegate, SlowSqlCollector collector, String source, SlowSqlThreshold threshold) {
        this(delegate, collector, source, threshold == null ? 0 : threshold.getThresholdMillis());
    }

    /**
     * 返回计时的 {@link Statement} 代理。
     *
     * @return Statement 计时代理
     * @throws SQLException 底层创建失败时抛出
     */
    public Statement createStatement() throws SQLException {
        return timedProxy(Statement.class, delegate.createStatement(), null);
    }

    /**
     * 返回计时的 {@link PreparedStatement} 代理（SQL 绑定于创建时）。
     *
     * @param sql 预编译 SQL 文本
     * @return PreparedStatement 计时代理
     * @throws SQLException 底层创建失败时抛出
     */
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return timedProxy(PreparedStatement.class, delegate.prepareStatement(sql), sql);
    }

    /**
     * 返回插桩后的 {@link Connection} 代理：createStatement / prepareStatement
     * 返回计时代理，其余方法透传底层连接。
     *
     * @return Connection 插桩代理
     */
    @SuppressWarnings("unchecked")
    public Connection proxyConnection() {
        return (Connection) Proxy.newProxyInstance(
                SlowSqlJdbcWrapper.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("createStatement".equals(method.getName())) {
                            return createStatement();
                        }
                        if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                                && args[0] instanceof String) {
                            return prepareStatement((String) args[0]);
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause() != null ? e.getCause() : e;
                        }
                    }
                });
    }

    /**
     * 返回底层真实连接（未插桩）。
     *
     * @return 底层连接
     */
    public Connection unwrapDelegate() {
        return delegate;
    }

    /**
     * 构造指定 JDBC 接口的计时代理。
     *
     * @param <T>          接口类型（Statement / PreparedStatement）
     * @param iface        JDBC 接口 Class
     * @param target       底层真实对象
     * @param boundSql     PreparedStatement 的绑定 SQL；Statement 传 null（由 execute 参数取得）
     * @return 计时代理
     */
    private <T> T timedProxy(Class<T> iface, final T target, final String boundSql) {
        final SlowSqlJdbcWrapper outer = this;
        return iface.cast(Proxy.newProxyInstance(
                SlowSqlJdbcWrapper.class.getClassLoader(),
                new Class<?>[]{iface},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if (!TIMED_METHODS.contains(name)) {
                            try {
                                return method.invoke(target, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause() != null ? e.getCause() : e;
                            }
                        }
                        // 提取 SQL 文本：PreparedStatement 用绑定 SQL；Statement 取首个 String 参数
                        String sql = boundSql;
                        if (sql == null && args != null) {
                            for (Object arg : args) {
                                if (arg instanceof String) {
                                    sql = (String) arg;
                                    break;
                                }
                            }
                        }
                        long startNanos = System.nanoTime();
                        Object result;
                        try {
                            result = method.invoke(target, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            // 执行异常：原样抛出且不采集（失败执行不入统计，避免污染耗时分布）
                            throw e.getCause() != null ? e.getCause() : e;
                        }
                        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
                        if (elapsedMillis >= outer.thresholdMillis) {
                            outer.collector.collect(outer.buildRecord(sql, name, elapsedMillis, result));
                        }
                        return result;
                    }
                }));
    }

    /**
     * 构建慢 SQL 记录。
     *
     * @param sql           SQL 文本
     * @param methodName    执行方法名
     * @param elapsedMillis 耗时（毫秒）
     * @param result        执行返回值（用于推断行数）
     * @return 慢 SQL 记录
     */
    private SlowSqlRecord buildRecord(String sql, String methodName, long elapsedMillis, Object result) {
        long rows = inferRows(result);
        long now = System.currentTimeMillis();
        boolean exceeded = elapsedMillis >= thresholdMillis;
        String text = sql == null ? methodName : sql;
        return new SlowSqlRecord(text, source, elapsedMillis, rows, now, exceeded);
    }

    /**
     * 从执行返回值推断行数：executeUpdate 返回影响行数；executeBatch 汇总正数元素；
     * executeQuery（ResultSet 不遍历）记 0；其他记 0。
     *
     * @param result 执行返回值
     * @return 行数
     */
    static long inferRows(Object result) {
        if (result instanceof Integer) {
            return (Integer) result;
        }
        if (result instanceof Long) {
            return (Long) result;
        }
        if (result instanceof int[]) {
            long sum = 0;
            for (int v : (int[]) result) {
                if (v > 0) {
                    sum += v;
                }
            }
            return sum;
        }
        if (result instanceof long[]) {
            long sum = 0;
            for (long v : (long[]) result) {
                if (v > 0) {
                    sum += v;
                }
            }
            return sum;
        }
        // ResultSet（executeQuery）不遍历统计行数，避免额外开销
        return 0;
    }
}
