package org.dbdatasync.parser.ddl;

import org.dbdatasync.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wave24：DDL schema history（表结构变更历史）。
 *
 * <p>Wave23 的表级 DDL 只做「识别 + 人工确认标记」，没有历史、没有顺序保障，
 * 也没有任何自动同步路径。本类提供可持久化的 DDL 事件账本：全局单调序号、
 * 每条 DDL 的源/目标 SQL、状态机（pending / confirmed / applied / failed）、
 * 以及「该表 DDL 未处理完」的阻塞标记，供 DDL-DML 顺序一致性守卫使用。</p>
 *
 * <p>存储为 UTF-8 JSON Lines 单文件追加，不新增表结构、不引入依赖；
 * 文件不可用时退化为纯内存账本并告警。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class SchemaHistory {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_NEEDS_TARGET_SQL = "needs_target_sql";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_FAILED = "failed";

    private static final Logger logger = LoggerFactory.getLogger(SchemaHistory.class);
    private static final SchemaHistory INSTANCE = new SchemaHistory();

    /** 单条 DDL 历史 */
    public static final class Entry {
        private String id;
        private long seq;
        private String metaId;
        private String tableName;
        private String operation;
        private String sourceSql;
        private String targetSql;
        private long sourceEventTime;
        private String status = STATUS_PENDING;
        private String message = "";
        private String confirmBy = "";
        private long createdAt = System.currentTimeMillis();

        public String getId() { return id; }
        public long getSeq() { return seq; }
        public String getMetaId() { return metaId; }
        public String getTableName() { return tableName; }
        public String getOperation() { return operation; }
        public String getSourceSql() { return sourceSql; }
        public String getTargetSql() { return targetSql; }
        public long getSourceEventTime() { return sourceEventTime; }
        public String getStatus() { return status; }
        public String getMessage() { return message; }
        public String getConfirmBy() { return confirmBy; }
        public long getCreatedAt() { return createdAt; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("seq", seq);
            m.put("metaId", metaId);
            m.put("tableName", tableName);
            m.put("operation", operation);
            m.put("sourceSql", sourceSql);
            m.put("targetSql", targetSql);
            m.put("sourceEventTime", sourceEventTime);
            m.put("status", status);
            m.put("message", message);
            m.put("confirmBy", confirmBy);
            m.put("createdAt", createdAt);
            return m;
        }

        static Entry fromMap(Map<String, Object> m) {
            Entry e = new Entry();
            e.id = str(m.get("id"));
            e.seq = num(m.get("seq"));
            e.metaId = str(m.get("metaId"));
            e.tableName = str(m.get("tableName"));
            e.operation = str(m.get("operation"));
            e.sourceSql = str(m.get("sourceSql"));
            e.targetSql = str(m.get("targetSql"));
            e.sourceEventTime = num(m.get("sourceEventTime"));
            e.status = str(m.get("status"));
            e.message = str(m.get("message"));
            e.confirmBy = str(m.get("confirmBy"));
            e.createdAt = num(m.get("createdAt"));
            return e;
        }

        private static String str(Object v) {
            return v == null ? "" : String.valueOf(v);
        }

        private static long num(Object v) {
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
            try {
                return v == null ? 0L : Long.parseLong(String.valueOf(v));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }

    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<Entry>());
    private final Set<String> blockedTables = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private volatile File historyFile;

    private SchemaHistory() {
    }

    public static SchemaHistory current() {
        return INSTANCE;
    }

    /** 配置文件目录并回放历史；目录不可用时保持纯内存。 */
    public synchronized void configure(String dir) {
        if (historyFile != null) {
            return; // 只回放一次，避免运行期重复加载
        }
        if (dir == null || dir.trim().isEmpty()) {
            return;
        }
        try {
            File folder = new File(dir.trim());
            if (!folder.exists() && !folder.mkdirs()) {
                logger.warn("schema history dir not writable: {}", folder);
                return;
            }
            historyFile = new File(folder, "schema-history.log");
            if (!historyFile.exists()) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Files.newInputStream(historyFile.toPath()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    try {
                        Map<String, Object> parsed = JsonUtil.parseMap(line);
                        if (parsed != null) {
                            Entry e = Entry.fromMap(parsed);
                            entries.add(e);
                            sequence.accumulateAndGet(e.seq, Math::max);
                            if (!STATUS_APPLIED.equals(e.status)) {
                                blockedTables.add(e.tableName);
                            }
                        }
                    } catch (Exception ignored) {
                        logger.warn("skip malformed schema history line");
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("load schema history failed, fallback to memory: {}", e.getMessage());
            historyFile = null;
        }
    }

    /** 追加一条 DDL 记录（分配全局单调序号并落盘）。 */
    public synchronized Entry append(String metaId, String tableName, String operation,
                                     String sourceSql, String targetSql, long sourceEventTime,
                                     boolean manualRequired) {
        Entry e = new Entry();
        e.seq = sequence.incrementAndGet();
        e.id = "ddl-" + e.seq;
        e.metaId = metaId == null ? "" : metaId;
        e.tableName = tableName == null ? "" : tableName;
        e.operation = operation == null ? "" : operation;
        e.sourceSql = sourceSql == null ? "" : sourceSql;
        e.targetSql = targetSql == null ? "" : targetSql;
        e.sourceEventTime = sourceEventTime;
        e.status = manualRequired ? STATUS_PENDING
                : (e.targetSql.isEmpty() ? STATUS_NEEDS_TARGET_SQL : STATUS_PENDING);
        e.message = manualRequired ? "高风险 DDL，必须人工确认后执行" : "待确认/自动同步";
        entries.add(e);
        // 未完成 DDL 阻塞同表 DML，保证 DDL-DML 顺序一致性
        blockedTables.add(e.tableName);
        persist(e);
        return e;
    }

    /**
     * Wave32：登记一条「已处理完毕」的 DDL 事件（列级 ALTER / 执行失败都记账，
     * 但不阻塞该表 DML）。此前只有 CREATE/DROP/TRUNCATE 等表级 DDL 会入账，
     * 最常见的 ALTER TABLE ADD COLUMN 完全不留痕，结构变更页恒 0。
     */
    public synchronized Entry recordEvent(String metaId, String tableName, String operation,
                                          String sourceSql, String targetSql, long sourceEventTime,
                                          String status, String message) {
        Entry e = new Entry();
        e.seq = sequence.incrementAndGet();
        e.id = "ddl-" + e.seq;
        e.metaId = metaId == null ? "" : metaId;
        e.tableName = tableName == null ? "" : tableName;
        e.operation = operation == null ? "" : operation;
        e.sourceSql = sourceSql == null ? "" : sourceSql;
        e.targetSql = targetSql == null ? "" : targetSql;
        e.sourceEventTime = sourceEventTime;
        e.status = status == null ? STATUS_APPLIED : status;
        e.message = message == null ? "" : message;
        entries.add(e);
        if (!STATUS_APPLIED.equals(e.status) && !STATUS_CONFIRMED.equals(e.status)) {
            blockedTables.add(e.tableName);
        }
        persist(e);
        return e;
    }

    /** Wave32：结构变更账本优先从 H2 统一账本回放（原有文件日志保持兼容）。 */
    private volatile boolean ledgerLoaded;

    private synchronized void ensureLedgerLoaded() {
        if (ledgerLoaded) {
            return;
        }
        ledgerLoaded = true;
        if (!org.dbdatasync.common.ledger.RuntimeLedger.isReady()) {
            return;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        synchronized (entries) {
            for (Entry e : entries) {
                seen.add(e.id);
            }
        }
        List<org.dbdatasync.common.ledger.RuntimeLedger.Entry> rows =
                org.dbdatasync.common.ledger.RuntimeLedger.list(
                        org.dbdatasync.common.ledger.RuntimeLedger.KIND_SCHEMA_HISTORY, null, 1000);
        for (int i = rows.size() - 1; i >= 0; i--) {
            try {
                Map<String, Object> parsed = JsonUtil.parseMap(rows.get(i).getPayload());
                if (parsed == null) {
                    continue;
                }
                Entry e = Entry.fromMap(parsed);
                if (e == null || seen.contains(e.id)) {
                    continue;
                }
                seen.add(e.id);
                entries.add(e);
                sequence.accumulateAndGet(e.seq, Math::max);
                if (!STATUS_APPLIED.equals(e.status)) {
                    blockedTables.add(e.tableName);
                }
            } catch (Exception ignored) {
                logger.warn("skip malformed runtime ledger schema history row");
            }
        }
    }

    public List<Entry> list() {
        ensureLedgerLoaded();
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public Entry find(String id) {
        synchronized (entries) {
            for (Entry e : entries) {
                if (e.id.equals(id)) {
                    return e;
                }
            }
        }
        return null;
    }

    public boolean isBlocked(String tableName) {
        return tableName != null && blockedTables.contains(tableName);
    }

    public void unblock(String tableName) {
        if (tableName != null) {
            blockedTables.remove(tableName);
        }
    }

    public synchronized boolean confirm(String id, String operator, String overrideTargetSql) {
        Entry e = find(id);
        if (e == null || STATUS_APPLIED.equals(e.status)) {
            return false;
        }
        if (overrideTargetSql != null && !overrideTargetSql.trim().isEmpty()) {
            e.targetSql = overrideTargetSql.trim();
        }
        e.status = STATUS_CONFIRMED;
        e.confirmBy = operator == null ? "unknown" : operator;
        e.message = "已由 " + e.confirmBy + " 确认，等待应用";
        persist(e);
        return true;
    }

    public synchronized void markApplied(String id, String message) {
        Entry e = find(id);
        if (e == null) {
            return;
        }
        e.status = STATUS_APPLIED;
        e.message = message == null ? "applied" : message;
        unblock(e.tableName);
        persist(e);
    }

    public synchronized void markFailed(String id, String message) {
        Entry e = find(id);
        if (e == null) {
            return;
        }
        e.status = STATUS_FAILED;
        e.message = message == null ? "failed" : message;
        persist(e);
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Entry> all = list();
        m.put("total", all.size());
        m.put("applied", count(all, STATUS_APPLIED));
        m.put("pending", count(all, STATUS_PENDING));
        m.put("needsTargetSql", count(all, STATUS_NEEDS_TARGET_SQL));
        m.put("confirmed", count(all, STATUS_CONFIRMED));
        m.put("failed", count(all, STATUS_FAILED));
        m.put("blockedTables", new ArrayList<>(blockedTables));
        m.put("persistent", historyFile != null);
        return m;
    }

    private static long count(List<Entry> all, String status) {
        long n = 0;
        for (Entry e : all) {
            if (status.equals(e.status)) {
                n++;
            }
        }
        return n;
    }

    /** 追加写（每行一条完整快照，重启回放以最后一条为准） */
    private void persist(Entry e) {
        String json = JsonUtil.objToJson(e.toMap());
        // Wave32：结构变更统一进 H2 运行态账本；即使 historyFile 尚未配置
        // （例如从未发生表级 DDL），也保证「所有数据都在数据库里」。
        if (org.dbdatasync.common.ledger.RuntimeLedger.isReady()) {
            org.dbdatasync.common.ledger.RuntimeLedger.append(
                    org.dbdatasync.common.ledger.RuntimeLedger.KIND_SCHEMA_HISTORY,
                    e.tableName, json);
        }
        File file = historyFile;
        if (file == null) {
            return;
        }
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            writer.write(json);
            writer.write('\n');
        } catch (Exception ex) {
            logger.warn("persist schema history failed: {}", ex.getMessage());
        }
    }
}
