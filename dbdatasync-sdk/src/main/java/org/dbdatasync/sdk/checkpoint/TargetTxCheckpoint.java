/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.checkpoint;

import org.dbdatasync.sdk.connector.database.DatabaseTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wave24：目标库事务内位点表（把 checkpoint 写进数据写入的同一个 JDBC 事务）。
 *
 * <p>Wave23 的 {@link TransactionalCheckpoint} 是「数据写入成功 → 再原子写位点文件」，
 * 两步跨存储，进程在两步之间崩溃时无法保证跨库原子性，报告里明确写了「向
 * exactly-once 收敛，非强一致」。本类补上缺失的那一半：</p>
 *
 * <pre>
 *   setAutoCommit(false)
 *     → batchUpdate(业务数据)
 *     → UPDATE/INSERT __DBSYNC_CHECKPOINT(位点 + 幂等键)   ← 同一 Connection / 同一事务
 *   commit()  /  rollback()
 * </pre>
 *
 * <p>因此目标端数据与位点要么一起提交、要么一起回滚。位点表写入失败会抛出异常
 * 触发 rollback，绝不出现「数据已落地但位点丢了」；文件 checkpoint 仍双写作为
 * 恢复兼容与运维备份，但权威位点以目标库表为准（{@link #recover}）。</p>
 *
 * <p>安全边界：默认关闭（{@code enableCheckpointInTargetTx=false}）；启用前需在目标库
 * 预建表（{@link #CREATE_TABLE_SQL}，首次写入会在事务外尝试建表）；不支持
 * {@code CREATE TABLE IF NOT EXISTS} 的方言需由运维手工建表。DDL 严格在事务外执行，
 * 避免 MySQL 等库隐式提交破坏原子性。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class TargetTxCheckpoint {

    public static final String TABLE = "__DBSYNC_CHECKPOINT";

    public static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "META_ID VARCHAR(128) PRIMARY KEY, "
            + "NEXT_FILE_NAME VARCHAR(512), "
            + "POSITION_VALUE VARCHAR(512), "
            + "SEQ_VALUE BIGINT, "
            + "EVENT_TIME BIGINT, "
            + "EVENT_TIME_SOURCE VARCHAR(32), "
            + "IDEMPOTENCY_KEY VARCHAR(256), "
            + "COMMITTED_AT BIGINT)";

    private static final String UPDATE_SQL = "UPDATE " + TABLE + " SET NEXT_FILE_NAME=?, POSITION_VALUE=?, "
            + "SEQ_VALUE=?, EVENT_TIME=?, EVENT_TIME_SOURCE=?, IDEMPOTENCY_KEY=?, COMMITTED_AT=? WHERE META_ID=?";

    private static final String INSERT_SQL = "INSERT INTO " + TABLE + " (META_ID, NEXT_FILE_NAME, POSITION_VALUE, "
            + "SEQ_VALUE, EVENT_TIME, EVENT_TIME_SOURCE, IDEMPOTENCY_KEY, COMMITTED_AT) VALUES (?,?,?,?,?,?,?,?)";

    private static final String SELECT_SQL = "SELECT META_ID, NEXT_FILE_NAME, POSITION_VALUE, SEQ_VALUE, "
            + "EVENT_TIME, EVENT_TIME_SOURCE, IDEMPOTENCY_KEY, COMMITTED_AT FROM " + TABLE + " WHERE META_ID=?";

    private static final Logger logger = LoggerFactory.getLogger(TargetTxCheckpoint.class);
    private static final AtomicBoolean TABLE_ENSURED = new AtomicBoolean(false);
    private static final ThreadLocal<CheckpointRecord> PENDING = new ThreadLocal<>();
    private static volatile boolean enabled = false;
    private static volatile java.util.function.BooleanSupplier enabledProvider;

    private TargetTxCheckpoint() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** 安装运行期开关提供者（系统配置保存后即时生效，无需重启） */
    public static void installEnabledProvider(java.util.function.BooleanSupplier provider) {
        enabledProvider = provider;
    }

    public static boolean isEnabled() {
        java.util.function.BooleanSupplier provider = enabledProvider;
        return provider != null ? provider.getAsBoolean() : enabled;
    }

    /** 在分发本批变更之前登记待提交位点（跨线程池时由 writeBatch 透传同一个对象） */
    public static void begin(CheckpointRecord record) {
        if (enabled && record != null) {
            PENDING.set(record);
        }
    }

    public static CheckpointRecord current() {
        return enabled ? PENDING.get() : null;
    }

    public static void end() {
        PENDING.remove();
    }

    /** 事务外一次性建表；不支持 IF NOT EXISTS 的方言由运维预建，失败只告警。 */
    public static void ensureTable(DatabaseTemplate template) {
        if (!enabled || !TABLE_ENSURED.compareAndSet(false, true)) {
            return;
        }
        try {
            template.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                try (Statement st = connection.createStatement()) {
                    st.execute(CREATE_TABLE_SQL);
                }
                return Boolean.TRUE;
            });
        } catch (Exception e) {
            logger.warn("ensure {} failed (请按手册在目标库预建表): {}", TABLE, e.getMessage());
        }
    }

    /**
     * 在当前 JDBC 事务内写位点表。调用方必须处于 setAutoCommit(false) 的事务里；
     * 任何异常都会向上抛，由调用方 rollback，保证数据与位点原子。
     */
    public static void writeInCurrentTransaction(DatabaseTemplate template, CheckpointRecord record) {
        if (!enabled || record == null) {
            return;
        }
        template.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            long committedAt = record.getCommittedAt() > 0
                    ? record.getCommittedAt() : System.currentTimeMillis();
            int updated;
            try (PreparedStatement ps = connection.prepareStatement(UPDATE_SQL)) {
                ps.setString(1, record.getFileName());
                ps.setString(2, String.valueOf(record.getPosition()));
                ps.setLong(3, record.getSeq());
                ps.setLong(4, record.getSourceEventTime());
                ps.setString(5, record.getEventTimeSource());
                ps.setString(6, record.getIdempotencyKey());
                ps.setLong(7, committedAt);
                ps.setString(8, record.getMetaId());
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                    ps.setString(1, record.getMetaId());
                    ps.setString(2, record.getFileName());
                    ps.setString(3, String.valueOf(record.getPosition()));
                    ps.setLong(4, record.getSeq());
                    ps.setLong(5, record.getSourceEventTime());
                    ps.setString(6, record.getEventTimeSource());
                    ps.setString(7, record.getIdempotencyKey());
                    ps.setLong(8, committedAt);
                    ps.executeUpdate();
                }
            }
            return Boolean.TRUE;
        });
    }

    /** 从目标库位点表读取权威恢复位点；表不存在或无记录返回 null。 */
    public static CheckpointRecord recover(Connection connection, String metaId) {
        if (metaId == null) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(SELECT_SQL)) {
            ps.setString(1, metaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                CheckpointRecord record = new CheckpointRecord(metaId, rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7));
                record.setCommittedAt(rs.getLong(8));
                return record;
            }
        } catch (SQLException e) {
            logger.warn("recover from {} failed: {}", TABLE, e.getMessage());
            return null;
        }
    }

}
