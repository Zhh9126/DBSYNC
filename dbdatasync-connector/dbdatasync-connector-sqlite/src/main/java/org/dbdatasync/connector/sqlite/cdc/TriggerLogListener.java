/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlite.cdc;

import org.dbdatasync.sdk.constant.ConnectorConstant;
import org.dbdatasync.sdk.listener.AbstractListener;
import org.dbdatasync.sdk.listener.event.RowChangedEvent;
import org.dbdatasync.sdk.model.ChangedOffset;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.connector.database.DatabaseConnectorInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wave24：Trigger CDC（早期 P1-6 只有设计，从未实现）。
 *
 * <p>对没有逻辑复制日志的 SQLite 源库，用「源表 AFTER INSERT/UPDATE/DELETE 触发器
 * + 影子日志表」捕获行变更：触发器把变更写成逐列 VARCHAR 文本（不依赖 SQLite JSON1
 * 扩展），本监听器按单调 {@code seq} 轮询影子表、还原为 {@link RowChangedEvent}，
 * 位点即最后成功发送的 seq，重启后从快照续读，并清理已消费日志。</p>
 *
 * <p>设计约束：不修改业务表结构、不加第三方依赖、只读源表元数据；
 * 触发器由 {@code installTriggers} 幂等创建（IF NOT EXISTS），可在离线单测里
 * 用真实 SQLite 库跑通「插入→捕获→还原→清理」全链路。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class TriggerLogListener extends AbstractListener<DatabaseConnectorInstance> {

    public static final String LOG_TABLE = "__DBSYNC_CDC_LOG";
    public static final String SEQ_KEY = "trigger_cdc_seq";
    private static final int BATCH_SIZE = 500;
    private static final long POLL_MILLIS = 200L;

    private static final Logger logger = LoggerFactory.getLogger(TriggerLogListener.class);

    private final AtomicLong lastSeq = new AtomicLong(-1L);
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean connected;
    /** 可注入的连接来源：生产用 ConnectorInstance，离线测试/轻量部署可直接给 JDBC 连接 */
    private volatile ConnectionSupplier connectionSupplier;
    /** 可注入的事件出口：离线测试/嵌入式运行可直接消费事件，不必启动 Watcher */
    private volatile java.util.function.Consumer<RowChangedEvent> eventSink;

    public void setEventSink(java.util.function.Consumer<RowChangedEvent> eventSink) {
        this.eventSink = eventSink;
    }

    /** 连接来源（每次调用返回一个可用连接，由调用方在 try-with-resources 中关闭） */
    public interface ConnectionSupplier {
        Connection get() throws Exception;
    }

    public void setConnectionSupplier(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
    }

    private Connection connection() throws Exception {
        ConnectionSupplier supplier = connectionSupplier;
        return supplier != null ? supplier.get() : getConnectorInstance().getConnection();
    }

    @Override
    public Map<String, String> captureSnapshot() {
        try (Connection c = connection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM " + LOG_TABLE)) {
            long seq = rs.next() ? rs.getLong(1) : 0L;
            lastSeq.set(seq);
            Map<String, String> snap = new LinkedHashMap<>();
            snap.put(SEQ_KEY, String.valueOf(seq));
            logger.info("Trigger CDC 捕获位点 seq={}", seq);
            return snap;
        } catch (Exception e) {
            logger.warn("capture trigger cdc snapshot failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    public void start() {
        if (snapshot != null && snapshot.get(SEQ_KEY) != null) {
            try {
                lastSeq.set(Long.parseLong(snapshot.get(SEQ_KEY)));
            } catch (NumberFormatException ignored) {
                // 非法快照按 -1（全量读取）处理
            }
        }
        try {
            installSchema();
            installTriggers();
        } catch (Exception e) {
            errorEvent(e);
            logger.error("install trigger cdc schema failed: {}", e.getMessage(), e);
            return;
        }
        connected = true;
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trigger-cdc-poll");
            t.setDaemon(true);
            return t;
        });
        scheduler = executor;
        executor.scheduleWithFixedDelay(this::pollSafely, 0L, POLL_MILLIS, TimeUnit.MILLISECONDS);
        logger.info("Trigger CDC 已启动（{} 表，轮询 {}ms）", tableCount(), POLL_MILLIS);
    }

    @Override
    public void close() {
        connected = false;
        ScheduledExecutorService executor = scheduler;
        if (executor != null) {
            executor.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public void refreshEvent(ChangedOffset offset) {
        if (offset == null || offset.getPosition() == null) {
            return;
        }
        try {
            long seq = Long.parseLong(String.valueOf(offset.getPosition()));
            lastSeq.accumulateAndGet(seq, Math::max);
            if (snapshot != null) {
                snapshot.put(SEQ_KEY, String.valueOf(seq));
            }
        } catch (NumberFormatException ignored) {
            // 非 seq 位点忽略
        }
    }

    /** 幂等建影子表 */
    public void installSchema() throws Exception {
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + LOG_TABLE + " ("
                    + "seq INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "tbl TEXT NOT NULL, op TEXT NOT NULL, pk TEXT, payload TEXT, ts INTEGER)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS __dbsync_cdc_seq_idx ON " + LOG_TABLE + "(seq)");
        }
    }

    /** 为每个监听表创建三类触发器（逐列 quote 拼接，不依赖 JSON1） */
    public void installTriggers() throws Exception {
        List<Table> tables = sourceTable == null ? Collections.emptyList() : sourceTable;
        try (Connection c = connection(); Statement st = c.createStatement()) {
            for (Table table : tables) {
                String name = table.getName();
                if (filterTable != null && !filterTable.isEmpty() && !filterTable.contains(name)) {
                    continue;
                }
                List<Field> columns = table.getColumn();
                if (columns == null || columns.isEmpty()) {
                    continue;
                }
                st.executeUpdate(triggerSql(name, ConnectorConstant.OPERTION_INSERT, "NEW", columns));
                st.executeUpdate(triggerSql(name, ConnectorConstant.OPERTION_UPDATE, "NEW", columns));
                st.executeUpdate(triggerSql(name, ConnectorConstant.OPERTION_DELETE, "OLD", columns));
            }
        }
    }

    private String triggerSql(String table, String operation, String rowAlias, List<Field> columns) {
        String suffix = operation.toLowerCase(java.util.Locale.ROOT);
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                payload.append(" || ';' || ");
            }
            payload.append("'").append(columns.get(i).getName()).append("=' || quote(")
                    .append(rowAlias).append(".\"").append(columns.get(i).getName()).append("\")");
        }
        String pk = columns.get(0).getName();
        return "CREATE TRIGGER IF NOT EXISTS \"__dbsync_cdc_" + suffix + "_" + sanitize(table) + "\""
                + " AFTER " + operation.toUpperCase(java.util.Locale.ROOT) + " ON \"" + table + "\" BEGIN"
                + " INSERT INTO " + LOG_TABLE + "(tbl,op,pk,payload,ts) VALUES ('" + table + "','"
                + operation.toUpperCase(java.util.Locale.ROOT) + "', quote(" + rowAlias + ".\"" + pk + "\"), "
                + payload + ", strftime('%s','now')*1000); END";
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private void pollSafely() {
        if (!connected) {
            return;
        }
        try {
            poll();
        } catch (Exception e) {
            logger.error("trigger cdc poll failed: {}", e.getMessage(), e);
        }
    }

    /** 读取一批日志并还原为行变更事件；成功后推进位点并清理已消费日志。 */
    public int poll() throws Exception {
        long cursor = lastSeq.get();
        List<Map<String, Object>> rows = new ArrayList<>();
        long maxSeq = cursor;
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("SELECT seq,tbl,op,pk,payload,ts FROM "
                     + LOG_TABLE + " WHERE seq > ? ORDER BY seq LIMIT " + BATCH_SIZE)) {
            ps.setLong(1, cursor);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seq", rs.getLong(1));
                    row.put("tbl", rs.getString(2));
                    row.put("op", rs.getString(3));
                    row.put("pk", rs.getString(4));
                    row.put("payload", rs.getString(5));
                    row.put("ts", rs.getLong(6));
                    rows.add(row);
                    maxSeq = rs.getLong(1);
                }
            }
        }
        for (Map<String, Object> row : rows) {
            emit(toEvent(row));
        }
        if (maxSeq > cursor) {
            lastSeq.set(maxSeq);
            if (snapshot != null) {
                snapshot.put(SEQ_KEY, String.valueOf(maxSeq));
                forceFlushEvent();
            }
            try (Connection c = connection();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM " + LOG_TABLE + " WHERE seq <= ?")) {
                ps.setLong(1, maxSeq);
                ps.executeUpdate();
            }
        }
        return rows.size();
    }

    private RowChangedEvent toEvent(Map<String, Object> row) {
        String table = String.valueOf(row.get("tbl"));
        String op = String.valueOf(row.get("op"));
        long seq = ((Number) row.get("seq")).longValue();
        Map<String, String> values = parsePayload(String.valueOf(row.get("payload")));
        List<Object> data = new ArrayList<>();
        Table definition = findTable(table);
        if (definition != null && definition.getColumn() != null) {
            for (Field field : definition.getColumn()) {
                data.add(unquote(values.get(field.getName())));
            }
        } else {
            data.addAll(new ArrayList<>(values.values()));
        }
        // op 由触发器写入时为 INSERT/UPDATE/DELETE，与 ConnectorConstant 大写值保持一致
        RowChangedEvent event = new RowChangedEvent(table, op.toUpperCase(java.util.Locale.ROOT),
                data, LOG_TABLE, seq);
        org.dbdatasync.sdk.model.SourceEventTime.apply(event, ((Number) row.get("ts")).longValue(),
                org.dbdatasync.sdk.model.ChangedOffset.EVENT_TIME_SOURCE);
        return event;
    }

    private Table findTable(String name) {
        if (sourceTable == null) {
            return null;
        }
        for (Table t : sourceTable) {
            if (name.equals(t.getName())) {
                return t;
            }
        }
        return null;
    }

    private void emit(RowChangedEvent event) {
        java.util.function.Consumer<RowChangedEvent> sink = eventSink;
        if (sink != null) {
            sink.accept(event);
            return;
        }
        int retry = 0;
        while (connected && retry < 100) {
            try {
                changeEvent(event);
                return;
            } catch (Exception e) {
                // 队列溢出等可恢复异常短暂退避后重投；其它异常也避免无限自旋
                retry++;
                sleepInMills(1L);
            }
        }
        if (connected) {
            logger.error("Trigger CDC 事件投递失败（重试 100 次）: table={}, seq={}",
                    event.getSourceTableName(), event.getChangedOffset().getPosition());
        }
    }

    /** 解析 {@code col='v';col2=NULL} 形式的逐列文本 */
    static Map<String, String> parsePayload(String payload) {
        Map<String, String> values = new HashMap<>();
        if (payload == null || payload.isEmpty()) {
            return values;
        }
        for (String part : payload.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                values.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return values;
    }

    static String unquote(String raw) {
        if (raw == null || "NULL".equals(raw)) {
            return null;
        }
        if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length() - 1).replace("''", "'");
        }
        return raw;
    }

    private int tableCount() {
        return sourceTable == null ? 0 : sourceTable.size();
    }
}
