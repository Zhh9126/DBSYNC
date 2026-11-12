/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.retry;

import java.sql.SQLException;
import java.util.Locale;

/**
 * 写入失败分类与指数退避策略（纯算法，零第三方依赖，可离线单测）。
 *
 * <p>专业同步工具不会对所有异常一视同仁地重试：死锁、锁等待、连接闪断、超时属于
 * 瞬时故障，重试可自愈；主键冲突、字段类型不符、SQL 语法错误、表不存在属于永久
 * 故障，重试只会放大延迟并污染统计，应直接进入死信。无法判定的异常保守地不重试。
 *
 * @author wuji
 * @version 1.0.0
 */
public final class WriteRetryPolicy {

    public enum FailureKind {
        /** 瞬时故障：可自动重试 */
        TRANSIENT,
        /** 永久故障：重试无意义，直接死信 */
        PERMANENT,
        /** 无法判定：保守不重试 */
        UNKNOWN
    }

    /** 永久故障：异常类名 / SQLState / 消息特征 */
    private static final String[] PERMANENT_MARKERS = {
            "DuplicateKeyException", "DataIntegrityViolationException", "BadSqlGrammarException",
            "SQLIntegrityConstraintViolationException", "SQLSyntaxErrorException",
            "SQLDataException", "SQLFeatureNotSupportedException", "InvalidDataAccessApiUsageException",
            "duplicate key", "duplicate entry", "unique constraint", "primary key",
            "constraint violation", "foreign key", "not null constraint", "check constraint",
            "unknown column", "unknown table", "table doesn't exist", "table not exist",
            "doesn't have a default value", "data too long", "incorrect value",
            "data truncation", "out of range value", "invalid column",
            "syntax error", "sql syntax", "ora-00942", "ora-00904", "ora-01400", "ora-00001",
            "permission denied", "access denied", "no such table", "column not found"
    };

    /** 瞬时故障：异常类名 / SQLState / 消息特征 */
    private static final String[] TRANSIENT_MARKERS = {
            "TransientDataAccessResourceException", "TransientDataAccessException",
            "DeadlockLoserDataAccessException", "PessimisticLockingFailureException",
            "CannotAcquireLockException", "CannotSerializeTransactionException",
            "CannotGetJdbcConnectionException", "RecoverableDataAccessException",
            "SQLTransientException", "SQLTransientConnectionException", "SQLTimeoutException",
            "SQLNonTransientConnectionException", "ConnectException", "SocketTimeoutException",
            "SocketException", "EOFException", "InterruptedIOException", "TimeoutException",
            "deadlock", "lock wait timeout", "lock timeout", "lock busy",
            "connection reset", "connection refused", "connection closed", "connection timed out",
            "connection is not available", "communications link", "broken pipe",
            "too many connections", "read timed out", "timed out", "timeout",
            "temporarily unavailable", "try again", "retry", "resource busy",
            "ora-00060", "ora-08177", "ora-03113", "ora-03114", "ora-125",
            "could not serialize", "server has gone away", "max connect timeout"
    };

    /** 典型可重试 SQLState 前缀（SQL-92 类 08 连接异常、40 事务回滚、HYT00 超时） */
    private static final String[] TRANSIENT_SQL_STATES = {"08", "40", "40001", "40P01", "HYT00", "HY000", "57014", "57P"};

    private WriteRetryPolicy() {
    }

    /**
     * 判定异常类型。遍历 cause 链，只要有任一层次命中永久故障即判永久（永久优先），
     * 否则命中瞬时故障判瞬时。SQLState 单独再判一次。
     */
    public static FailureKind classify(Throwable error) {
        if (error == null) {
            return FailureKind.UNKNOWN;
        }
        StringBuilder text = new StringBuilder();
        StringBuilder states = new StringBuilder();
        int depth = 0;
        Throwable cursor = error;
        while (cursor != null && depth < 12) {
            text.append(cursor.getClass().getName()).append(' ');
            if (cursor.getMessage() != null) {
                text.append(cursor.getMessage()).append(' ');
            }
            if (cursor instanceof SQLException) {
                String state = ((SQLException) cursor).getSQLState();
                if (state != null) {
                    states.append(state).append(' ');
                }
                text.append(" errorCode=").append(((SQLException) cursor).getErrorCode()).append(' ');
            }
            cursor = cursor.getCause();
            depth++;
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);
        for (String marker : PERMANENT_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return FailureKind.PERMANENT;
            }
        }
        for (String marker : TRANSIENT_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return FailureKind.TRANSIENT;
            }
        }
        String stateText = states.toString();
        for (String state : TRANSIENT_SQL_STATES) {
            if (stateText.contains(state)) {
                return FailureKind.TRANSIENT;
            }
        }
        return FailureKind.UNKNOWN;
    }

    public static boolean isRetryable(Throwable error) {
        return classify(error) == FailureKind.TRANSIENT;
    }

    /**
     * 指数退避 + 抖动，避免同一时刻大量行一起重试造成目标库二次冲击。
     *
     * @param failedAttempt 已失败次数，从 1 开始
     * @param baseMillis    基础退避
     * @param capMillis     单次退避上限
     * @param random        [0,1) 随机因子，由调用方注入以便单测
     */
    public static long backoffMillis(int failedAttempt, long baseMillis, long capMillis, double random) {
        if (failedAttempt < 1 || baseMillis <= 0) {
            return 0L;
        }
        long ceiling = capMillis > 0 ? capMillis : Long.MAX_VALUE;
        long value = baseMillis;
        int shifts = Math.min(failedAttempt - 1, 30);
        for (int i = 0; i < shifts; i++) {
            if (value >= ceiling || value > Long.MAX_VALUE / 2) {
                value = ceiling;
                break;
            }
            value = value * 2;
        }
        if (value > ceiling) {
            value = ceiling;
        }
        double jitter = 0.5d + 0.5d * Math.max(0d, Math.min(1d, random));
        return (long) (value * jitter);
    }

    /**
     * 是否继续下一次重试。仅瞬时故障可重试，且受次数与单条总时间预算双重限制。
     */
    public static boolean shouldRetry(FailureKind kind, int failedAttempts, int maxAttempts,
                                      long elapsedMillis, long maxElapsedMillis) {
        return kind == FailureKind.TRANSIENT
                && failedAttempts < maxAttempts
                && elapsedMillis < maxElapsedMillis;
    }

    /**
     * 是否继续下一次重试（便捷重载）。
     */
    public static boolean shouldRetry(Throwable error, int failedAttempts, WriteRetrySettings settings,
                                      long elapsedMillis) {
        return settings.isEnabled()
                && shouldRetry(classify(error), failedAttempts, settings.getMaxAttempts(),
                elapsedMillis, settings.getMaxElapsedMillis());
    }
}
