/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.connector.oracle.logminer;

import org.dbdatasync.connector.oracle.OracleException;
import org.dbdatasync.sdk.util.DatabaseUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2023-12-09 20:21
 */
public class LogMiner {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Lock lock = new ReentrantLock();
    private final String username;
    private final String password;
    private final String url;
    private final String schema;
    private final String driverClassName;
    private volatile int queryTimeout = 300;

    // 动态 FetchSize 配置
    private final int minFetchSize = 500;
    private final int maxFetchSize = 5000;
    private volatile int currentFetchSize = 1000;

    // 动态休眠时间配置（毫秒）
    private final int minSleepMillis = 100;
    private final int maxSleepMillis = 3000;
    private volatile int currentSleepMillis = 1000;

    // 查询范围控制
    private volatile long currentScnRange = 20000;
    private final long MIN_SCN_RANGE = 10000;
    private final long MAX_SCN_RANGE = 100000;

    // 性能统计
    private volatile long lastQueryRows = 0;
    private volatile long consecutiveEmptyQueries = 0;
    private volatile long totalQueriesCount = 0;
    private volatile long totalRowsProcessed = 0;
    private volatile long totalEmptyQueries = 0;
    private volatile long lastStatsTime = System.currentTimeMillis();
    /**
     * 上次主动切换归档的时间戳。达梦：在线 redo 中未归档的提交对 mining 不可见，
     * 空轮询且有堆积时主动切归档，限制切换频率避免刷爆归档目录。
     */
    private volatile long lastArchiveSwitchAt = 0;

    private volatile boolean connected = false;
    private Connection connection;
    private List<BigInteger> currentRedoLogSequences;
    private final TransactionalBuffer transactionalBuffer = new TransactionalBuffer();
    // 已提交的位点
    private Long committedScn = 0L;
    // 初始位点
    private long startScn = 0;
    // 本轮查询处理的最大SCN，用于避免重复查询
    private long processedScn = 0;
    private EventListener listener;
    private Worker worker;
    /**
     * LogMiner 会话是否已启动。用于避免 redo 切换时频繁 START/END 导致 alert.log 暴涨。
     */
    private volatile boolean logMinerSessionStarted = false;
    /**
     * 是否为达梦连接（url 前缀 dm:），用于在 redo SQL 上做达梦兼容处理（如剥离 ROWID 谓词）。
     */
    private final boolean isDameng;

    public LogMiner(String username, String password, String url, String schema, String driverClassName) {
        this.username = username;
        this.password = password;
        this.url = url;
        this.schema = schema;
        this.driverClassName = driverClassName;
        this.isDameng = isDamengUrl(url);
    }

    /**
     * 判断 JDBC URL 是否为达梦（兼容 jdbc:dm:// 与 dm:// 两种前缀）。
     */
    static boolean isDamengUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase();
        return lower.startsWith("dm:") || lower.contains(":dm:");
    }

    public void close() {
        connected = false;
        closeQuietly();
    }

    private void closeQuietly() {
        if (isValid()) {
            LogMinerHelper.endLogMiner(connection);
        }
        if (null != worker && !worker.isInterrupted()) {
            worker.interrupt();
            worker = null;
        }
        close(connection);
    }

    public void start() throws SQLException {
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (locked && !connected) {
                connected = true;
                connect();
            } else {
                logger.error("LogMiner is already started");
            }
        } catch (InterruptedException e) {
            throw new OracleException(e);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private Connection createConnection() throws SQLException {
        return DatabaseUtil.getConnection(driverClassName, url, username, password);
    }

    private Connection validateConnection() throws SQLException {
        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection(driverClassName, url, username, password);
            LogMinerHelper.setSessionParameter(conn);
            int version = conn.getMetaData().getDatabaseMajorVersion();
            // 19支持cdb模式
            if (version == 19) {
                LogMinerHelper.setSessionContainerIfCdbMode(conn);
            }
            // 低于10不支持（达梦等已实现 DBMS_LOGMNR 协议的库放行，版本号语义与 Oracle 不同）
            else if (version < 10 && !LogMinerHelper.hasLogMinerPackage(conn)) {
                throw new IllegalArgumentException(String.format("Unsupported database version: %d(current) < 10", version));
            }
            // 检查账号权限
            LogMinerHelper.checkPermissions(conn, version);
        } catch (Exception e) {
            close(conn);
            throw e;
        }
        return conn;
    }

    private void connect() throws SQLException {
        this.connection = validateConnection();
        // 判断是否第一次读取
        if (startScn == 0) {
            startScn = LogMinerHelper.getCurrentScn(connection);
            restartLogMiner(startScn);
        } else {
            restartLogMiner(LogMinerHelper.getCurrentScn(connection));
        }
        logger.info("Start log miner, scn={}", startScn);
        worker = new Worker();
        worker.setName("log-miner-parser-" + url + "_" + worker.hashCode());
        worker.setDaemon(false);
        worker.start();
    }

    private void recover() {
        logger.error("Connection interrupted, attempting to reconnect");
        while (connected) {
            try {
                closeQuietly();
                connect();
                logger.info("Reconnect successfully");
                break;
            } catch (Exception e) {
                logger.error("Reconnect failed", e);
                sleepSeconds(5);
            }
        }
    }

    private void sleepSeconds(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            if (connected) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private void restartLogMiner(long endScn) throws SQLException {
        LogMinerHelper.startLogMiner(connection, startScn, endScn);
        logMinerSessionStarted = true;
        currentRedoLogSequences = LogMinerHelper.getCurrentRedoLogSequences(connection);
    }

    private boolean redoLogSwitchOccurred() throws SQLException {
        final List<BigInteger> newSequences = LogMinerHelper.getCurrentRedoLogSequences(connection);
        if (!newSequences.equals(currentRedoLogSequences)) {
            currentRedoLogSequences = newSequences;
            return true;
        }
        return false;
    }

    /**
     * redo 切换时只 add 新日志文件，不重启 LogMiner 会话。
     */
    private void addLogFilesIfNeeded(long endScn) throws SQLException {
        if (!logMinerSessionStarted) {
            restartLogMiner(endScn);
            return;
        }
        // 达梦兼容：向已启动的 LogMiner 会话追加日志会话失效，需整段重开会话
        if (LogMinerHelper.isDameng(connection)) {
            LogMinerHelper.endLogMiner(connection);
            logMinerSessionStarted = false;
            restartLogMiner(endScn);
            return;
        }
        LogMinerHelper.addLogFiles(connection, startScn, endScn);
    }

    private void logMinerViewProcessor(ResultSet rs) throws SQLException {
        long rowCount = 0;
        boolean rowDebug = Boolean.getBoolean("dbdatasync.logminer.rowdebug");
        while (rs.next()) {
            rowCount++;
            BigInteger scn = rs.getBigDecimal("SCN").toBigInteger();
            String tableName = rs.getString("TABLE_NAME");
            String segOwner = rs.getString("SEG_OWNER");
            int operationCode = rs.getInt("OPERATION_CODE");
            Timestamp changeTime = rs.getTimestamp("TIMESTAMP");
            String txId = rs.getString("XID");
            if (rowDebug) {
                logger.info("[logminer-rowdebug] scn={}, op={}, segOwner={}, table={}, txId={}", scn, operationCode, segOwner, tableName, txId);
            }
            if (scn.longValue() > processedScn) {
                processedScn = scn.longValue();
            }
            // Commit
            if (operationCode == LogMinerHelper.LOG_MINER_OC_COMMIT) {
                // 将TransactionalBuffer中当前事务的DML 转移到消费者处理
                transactionalBuffer.commit(txId, scn, committedScn);
                continue;
            }

            // Rollback
            if (operationCode == LogMinerHelper.LOG_MINER_OC_ROLLBACK) {
                // 清空TransactionalBuffer中当前事务
                if (transactionalBuffer.rollback(txId)) {
                    logger.debug("txId: {} rollback", txId);
                }
                continue;
            }

            // MISSING_SCN
            if (operationCode == LogMinerHelper.LOG_MINER_OC_MISSING_SCN) {
                logger.warn("Found MISSING_SCN");
                continue;
            }

            // DDL
            String redoSql = getRedoSQL(rs);
            if (operationCode == LogMinerHelper.LOG_MINER_OC_DDL) {
                updateCommittedScn(scn.longValue());
                listener.onEvent(new RedoEvent(scn.longValue(), operationCode, redoSql, segOwner, tableName, changeTime, txId));
                continue;
            }

            // DML
            if (operationCode == LogMinerHelper.LOG_MINER_OC_INSERT || operationCode == LogMinerHelper.LOG_MINER_OC_DELETE || operationCode == LogMinerHelper.LOG_MINER_OC_UPDATE) {
                // 内部维护 TransactionalBuffer，将每条DML注册到Buffer中
                // 根据事务提交或者回滚情况决定如何处理
                if (redoSql != null) {
                    final RedoEvent event = new RedoEvent(scn.longValue(), operationCode, redoSql, segOwner, tableName, changeTime, txId);
                    // Transactional Commit Callback
                    TransactionalBuffer.CommitCallback commitCallback = (smallestScn, commitScn, counter)-> {
                        if (smallestScn == null || scn.compareTo(smallestScn) < 0) {
                            // 当前SCN 事务已经提交 并且 小于事务缓冲区中所有的开始SCN，所以可以更新offsetScn
                            startScn = scn.longValue();
                        }
                        if (counter == 0) {
                            updateCommittedScn(commitScn.longValue());
                        }
                        event.setScn(startScn < committedScn ? committedScn : startScn);
                        listener.onEvent(event);
                    };
                    // 生成操作唯一标识：scn + 表名 + 操作类型 + SQL内容hash
                    // 用于防止重复查询导致的重复处理（同一事务的不同DML可能有相同SCN，所以不能只用SCN判断）
                    String operationId = scn + "_" + tableName + "_" + operationCode + "_" + redoSql.hashCode();
                    transactionalBuffer.registerCommitCallback(txId, scn, operationId, commitCallback);
                }
            }
        }
        // 保存本轮查询行数，用于动态调整
        lastQueryRows = rowCount;
    }

    private void updateCommittedScn(long newScn) {
        committedScn = newScn > committedScn ? newScn : committedScn;
    }

    /**
     * 动态调整 FetchSize
     * 优化策略：只有在真正空闲时才降低，避免误判
     */
    private void adjustFetchSize() {
        if (lastQueryRows == 0) {
            consecutiveEmptyQueries++;
            // 只有在连续大量空查询时才降低 fetchSize
            if (consecutiveEmptyQueries > 20 && currentFetchSize > minFetchSize) {
                currentFetchSize = Math.max(minFetchSize, (int) (currentFetchSize * 0.8));
                logger.debug("Reduce fetchSize to {} after {} consecutive empty queries", currentFetchSize, consecutiveEmptyQueries);
            }
        } else {
            consecutiveEmptyQueries = 0;
            // 有数据，根据查询行数动态调整
            if (lastQueryRows >= currentFetchSize * 0.9) {
                // 接近满载，增加 fetchSize
                int newSize = Math.min(maxFetchSize, currentFetchSize * 2);
                if (newSize != currentFetchSize) {
                    currentFetchSize = newSize;
                    logger.info("Increase fetchSize to {} (utilization: {}%)", currentFetchSize, (lastQueryRows * 100 / currentFetchSize));
                }
            }
            // 移除了过早降低的逻辑，保持稳定的 fetchSize
        }
    }

    /**
     * 动态调整 SCN 查询范围
     * 根据积压情况和查询性能自适应调整
     */
    private void adjustScnRange(long backlog, long queryDuration) {
        // 根据积压情况调整
        if (backlog > MAX_SCN_RANGE * 5) {
            // 严重积压，增大查询范围
            long newRange = Math.min(MAX_SCN_RANGE, currentScnRange * 2);
            if (newRange != currentScnRange) {
                currentScnRange = newRange;
                logger.info("Increase SCN range to {} due to large backlog: {}", currentScnRange, backlog);
            }
        } else if (backlog < MAX_SCN_RANGE && currentScnRange > MIN_SCN_RANGE) {
            // 积压不多，可以降低范围提高实时性
            long newRange = Math.max(MIN_SCN_RANGE, currentScnRange / 2);
            if (newRange != currentScnRange) {
                currentScnRange = newRange;
                logger.info("Decrease SCN range to {} (backlog: {})", currentScnRange, backlog);
            }
        }

        // 根据查询时长调整
        if (queryDuration > 60000) {
            // 查询超过1分钟，缩小范围
            long newRange = Math.max(MIN_SCN_RANGE, (long) (currentScnRange * 0.8));
            if (newRange != currentScnRange) {
                currentScnRange = newRange;
                logger.warn("Decrease SCN range to {} due to slow query ({}ms)", currentScnRange, queryDuration);
            }
        }
    }

    /**
     * 动态调整休眠时间
     * 根据数据量灵活调整轮询间隔，平衡延迟和CPU占用
     */
    private void adjustSleepTime() {
        if (lastQueryRows == 0) {
            // 无数据，逐步增加休眠时间
            int newSleep = Math.min(maxSleepMillis, currentSleepMillis + 200);
            if (newSleep != currentSleepMillis) {
                currentSleepMillis = newSleep;
                logger.debug("Increase sleep time to {}ms (no data)", currentSleepMillis);
            }
        } else if (lastQueryRows >= currentFetchSize * 0.8) {
            // 接近满载，缩短休眠时间，快速处理积压
            if (currentSleepMillis > minSleepMillis) {
                currentSleepMillis = minSleepMillis;
                logger.debug("Reduce sleep time to {}ms (high load)", currentSleepMillis);
            }
        } else {
            // 有数据但不多，适度休眠
            int newSleep = Math.max(minSleepMillis, Math.min(maxSleepMillis, 500));
            if (Math.abs(newSleep - currentSleepMillis) > 100) {
                currentSleepMillis = newSleep;
                logger.debug("Adjust sleep time to {}ms (moderate load)", currentSleepMillis);
            }
        }
    }

    /**
     * 收集性能统计信息
     * 每分钟输出一次统计报告
     */
    private void collectStatistics(long currentScn, long queryDuration) {
        totalQueriesCount++;
        totalRowsProcessed += lastQueryRows;
        if (lastQueryRows == 0) {
            totalEmptyQueries++;
        }

        long now = System.currentTimeMillis();
        if (now - lastStatsTime > 60000) { // 每分钟输出一次
            long avgRows = totalQueriesCount > 0 ? totalRowsProcessed / totalQueriesCount : 0;
            long emptyRate = totalQueriesCount > 0 ? totalEmptyQueries * 100 / totalQueriesCount : 0;
            long backlog = currentScn - startScn;

            logger.info("=== LogMiner Performance Stats (1min) ===");
            logger.info("Queries: {}, Rows: {}, Empty: {}", totalQueriesCount, totalRowsProcessed, totalEmptyQueries);
            logger.info("Avg rows/query: {}, Empty rate: {}%", avgRows, emptyRate);
            logger.info("Current fetchSize: {}, scnRange: {}, sleep: {}ms", currentFetchSize, currentScnRange, currentSleepMillis);
            logger.info("SCN backlog: {}, processing speed: {}/min", backlog, totalQueriesCount > 0 ? (totalRowsProcessed * 60 / totalQueriesCount) : 0);
            logger.info("Start SCN: {}, Current SCN: {}, Committed SCN: {}", startScn, currentScn, committedScn);
            logger.info("Buffer size: {}, Last query: {}ms", transactionalBuffer.isEmpty() ? 0 : "non-zero", queryDuration);
            logger.info("=========================================");

            // 重置统计（滚动窗口）
            totalQueriesCount = 0;
            totalRowsProcessed = 0;
            totalEmptyQueries = 0;
            lastStatsTime = now;
        }
    }

    private String getRedoSQL(ResultSet rs) throws SQLException {
        String redoSql = rs.getString("SQL_REDO");
        if (redoSql == null) {
            return null;
        }
        StringBuilder redoBuilder = new StringBuilder(redoSql);

        // https://docs.oracle.com/cd/B19306_01/server.102/b14237/dynviews_1154.htm#REFRN30132
        // Continuation SQL flag. Possible values are:
        // 0 = indicates SQL_REDO and SQL_UNDO is contained within the same row
        // 1 = indicates that either SQL_REDO or SQL_UNDO is greater than 4000 bytes in size and is continued in the next row returned by the view
        int csf = rs.getInt("CSF");

        while (csf == 1) {
            if (!rs.next()) {
                break;
            }
            // 仅 SQL_UNDO 超长续行时本行 SQL_REDO 为 null，禁止 append(null) 写入字面量 "null"
            String piece = rs.getString("SQL_REDO");
            if (piece != null) {
                redoBuilder.append(piece);
            }
            csf = rs.getInt("CSF");
        }

        String finalSql = redoBuilder.toString();
        // 达梦兼容：达梦 start_logmnr 无等效 NO_ROWID_IN_STMT 选项，UPDATE/DELETE 的 redo SQL
        // WHERE 中携带 `ROWID = '...'` 谓词；ROWID 为源库物理地址，在目标库必然不同，
        // 会导致 UPDATE/DELETE 影响 0 行，这里统一剥离 ROWID 谓词（Oracle 侧 redo 不含 ROWID，不受影响）。
        if (isDameng) {
            finalSql = stripRowidPredicate(finalSql);
        }
        return finalSql;
    }

    /**
     * 剥离 redo SQL WHERE 条件中的 ROWID 谓词。
     * 兼容 `... AND ROWID = 'xxx'` 与 `WHERE ROWID = 'xxx'` 两种形式。
     */
    static String stripRowidPredicate(String sql) {
        if (sql == null || sql.indexOf("ROWID") < 0) {
            return sql;
        }
        String stripped = sql.replaceAll("(?i)\\s+AND\\s+ROWID\\s*=\\s*'[^']*'", "")
                .replaceAll("(?i)(WHERE\\s+)ROWID\\s*=\\s*'[^']*'\\s*(AND\\s+)?", "$11 = 1 $2");
        return stripped;
    }

    public void registerEventListener(EventListener listener) {
        this.listener = listener;
    }

    public long getStartScn() {
        return startScn;
    }

    public void setStartScn(long startScn) {
        this.startScn = startScn;
    }

    public interface EventListener {

        void onEvent(RedoEvent redoEvent);
    }

    public boolean isConnected() {
        return connected;
    }

    // 判断连接是否正常
    private boolean isValid() {
        try {
            return connection != null && connection.isValid(queryTimeout);
        } catch (SQLException e) {
            return false;
        }
    }

    private void close(AutoCloseable closeable) {
        if (null != closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
        }
    }

    private long getNewStartScn(Long smallestUncommittedScn, long endScn) {
        long newStartScn;
        if (smallestUncommittedScn != null && committedScn > smallestUncommittedScn) {
            // 【关键保护】有未提交事务，且committedScn已经超过该事务的起始SCN
            // 如果推进到committedScn，会跳过未提交事务的后续操作
            // 只能推进到processedScn+1（已读取的最大SCN），不能跳跃
            if (processedScn > startScn) {
                newStartScn = processedScn + 1;
            } else {
                // 本轮没有新数据，保持不变，等待未提交事务的后续操作
                newStartScn = startScn;
            }
        } else if (committedScn > startScn) {
            // 有事务提交，且没有更早的未提交事务阻塞
            // 安全推进到committedScn
            newStartScn = committedScn;
        } else if (processedScn > startScn) {
            // 本轮读取了数据，但没有提交
            // 推进到processedScn+1，避免重复查询
            newStartScn = processedScn + 1;
        } else {
            // 没有读取到任何数据（processedScn == startScn）
            // 【关键】不能直接跳到endScn！Oracle LogMiner的归档数据可能有延迟出现在V$LOGMNR_CONTENTS
            // 平衡策略：
            // - 如果距离很近（<10），可能是归档延迟，保守推进
            // - 如果距离较远（>=10），可能是过滤条件导致的空窗口，适度推进
            long distance = endScn - startScn;
            if (distance < 10) {
                // 距离太近，可能有归档延迟，只推进一小步
                newStartScn = startScn + 1;
            } else {
                // 距离较远，适度推进到中间位置，平衡性能与安全性
                newStartScn = startScn + Math.min(distance / 2, 100);
            }
        }
        return newStartScn;
    }

    private PreparedStatement createStatement() throws SQLException {
        String minerViewQuery = LogMinerHelper.logMinerViewQuery(schema, username);
        // 达梦 V$LOGMNR_CONTENTS 返回顺序可能是"事务的 COMMIT 行先于 DML 行"，
        // 事务缓冲按提交驱动转发，必须保证按 SCN 升序读取（COMMIT 的 SCN 一定大于同事务的 DML）
        if (LogMinerHelper.isDameng(connection)) {
            minerViewQuery = minerViewQuery + " ORDER BY SCN ASC";
        }
        PreparedStatement statement = connection.prepareStatement(minerViewQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        statement.setQueryTimeout(queryTimeout);
        return statement;
    }



    final class Worker extends Thread {

        @Override
        public void run() {
            PreparedStatement cachedStatement = null;
            ResultSet rs = null;
            try {
                cachedStatement = createStatement();
                while (!isInterrupted() && connected) {
                    // 1. 检查连接有效性
                    if (!isValid()) {
                        close(cachedStatement);
                        connection = createConnection();
                        cachedStatement = createStatement();
                    }

                    // 关闭上一轮的 ResultSet（但复用 PreparedStatement）
                    close(rs);

                    // 2. 确定查询范围（动态调整 SCN 跨度）
                    long currentScn = LogMinerHelper.getCurrentScn(connection);
                    long endScn = Math.min(currentScn, startScn + currentScnRange);

                    // 3. 决定是否重建/追加日志文件——
                    //    Oracle: 仅在 redo 切换时处理（避免会话频繁重启刷爆 alert.log）；
                    //    达梦: 每轮重建会话（end + add 最新归档 + start）。
                    //    达梦 V$LOGMNR_CONTENTS 只覆盖会话启动时已加入的归档文件存量，
                    //    会话启动后仅到达已加入文件边界外的提交不可见（实测：插入后同会话查不到，
                    //    重开会话才可见），故必须每轮重开会话才能保证增量实时性。
                    if (isDameng) {
                        addLogFilesIfNeeded(endScn);
                    } else if (redoLogSwitchOccurred()) {
                        logger.info("Switch to new redo log");
                        addLogFilesIfNeeded(endScn);
                    }

                    // 4. 动态调整堆积偏移SCN
                    long queryStartTime = System.currentTimeMillis();

                    long backlog;
                    try {
                        backlog = findBacklogCount(startScn, currentScn);
                        if (backlog > currentScnRange * 2) {
                            logger.warn("start SCN: {}, currentScn, {}, SCN backlog: {}, scnRange: {}, recommend increase MAX_SCN_RANGE", startScn, currentScn, backlog, currentScnRange);
                        }

                        // 5. 查询 LogMiner 视图（复用 PreparedStatement）
                        processedScn = startScn;

                        // 6. 动态设置 fetchSize
                        cachedStatement.setFetchSize(currentFetchSize);
                        cachedStatement.setString(1, String.valueOf(startScn));
                        cachedStatement.setString(2, String.valueOf(endScn));

                        rs = cachedStatement.executeQuery();
                        logMinerViewProcessor(rs);
                    } catch (SQLException e) {
                        String oracleMsg = e.getMessage() == null ? "" : e.getMessage();
                        String upperMsg = oracleMsg.toUpperCase();
                        // 达梦兼容：向已启动会话 add_logfile 会使会话失效，重查 contents 报必须先 start_logmnr()；自愈为重启会话
                        if (oracleMsg.contains("ORA-00310") || oracleMsg.contains("-2844") || upperMsg.contains("START_LOGMNR()")) {
                            logger.info("LogMiner session invalidated, restart log miner: {}", oracleMsg);
                            LogMinerHelper.endLogMiner(connection);
                            logMinerSessionStarted = false;
                            restartLogMiner(endScn);
                            continue;
                        } else if (oracleMsg.contains("ORA-01013")) {
                            // 查询超时，缩小查询范围
                            logger.error("Query timeout after {}s, reducing SCN range", queryTimeout);
                            currentScnRange = Math.max(MIN_SCN_RANGE, currentScnRange / 2);
                            continue;
                        }
                        throw e;
                    }

                    long queryDuration = System.currentTimeMillis() - queryStartTime;

                    // 7. 推进 SCN
                    Long smallestUncommittedScn = transactionalBuffer.getSmallestScn();
                    long newStartScn = getNewStartScn(smallestUncommittedScn, endScn);

                    // 达梦兼容：本轮未挖到数据但 SCN 有堆积——提交可能仍未归档（mining 只见已加入归档）。
                    // 现场实测：提交后同会话/同归档边界不可见，需等归档切换后才可见。
                    // ① 主动切换归档让待归档提交尽快入档（限速避免刷爆归档目录）；
                    // ② startScn 只保守 +1 推进（禁止大步跳跃），确保不会跳过尚未归档的提交。
                    if (isDameng && lastQueryRows == 0 && backlog > 0 && transactionalBuffer.isEmpty()) {
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastArchiveSwitchAt >= 2L * currentSleepMillis) {
                            try {
                                LogMinerHelper.archiveLogSwitch(connection);
                                lastArchiveSwitchAt = nowMs;
                            } catch (SQLException switchEx) {
                                logger.warn("DM archive switch failed (will retry next poll): {}", switchEx.getMessage());
                            }
                        }
                        newStartScn = startScn + 1;
                    }

                    if (newStartScn != startScn) {
                        startScn = newStartScn;
                    }

                    // 当出现堆积时，快速推荐SCN，拿到下一个有效的SCN
                    if (currentScn - startScn > backlog * 100 || currentScn - startScn > currentScnRange * 2) {
                        newStartScn = findNextValidScn(startScn, connection);
                        if (newStartScn != startScn) {
                            startScn = newStartScn;
                        }
                    }

                    // 8. 性能统计和动态调整
                    collectStatistics(currentScn, queryDuration);
                    adjustFetchSize();
                    adjustScnRange(backlog, queryDuration);
                    adjustSleepTime();

                    // 9. 动态休眠
                    try {
                        TimeUnit.MILLISECONDS.sleep(currentSleepMillis);
                    } catch (InterruptedException e) {
                        if (connected) {
                            logger.error("Sleep interrupted: {}", e.getMessage());
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                if (connected) {
                    logger.error(e.getMessage(), e);
                    recover();
                }
            } finally {
                close(rs);
                close(cachedStatement);
            }
        }
    }

    public Long findNextValidScn(Long previousScn, Connection connection) throws SQLException {
        // 获取SQL查询语句
        String sql = LogMinerHelper.getNextValidScnAfter();

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            // 设置参数：传入上一个SCN
            pstmt.setLong(1, previousScn);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Long nextValidScn = rs.getLong("NEXT_VALID_SCN");
                    if (!rs.wasNull()) {
                        return nextValidScn;
                    }
                }
            }
        }
        return previousScn;
    }

    public Long findBacklogCount(Long startScn, Long currentScan) throws SQLException {
        // 获取SQL查询语句
        String sql = LogMinerHelper.getBacklogCount();

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            // 设置参数：传入上一个SCN
            pstmt.setLong(1, startScn);
            pstmt.setLong(2, currentScan);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("BACKLOG_COUNT");
                }
            }
        }
        return currentScan - startScn;
    }
}