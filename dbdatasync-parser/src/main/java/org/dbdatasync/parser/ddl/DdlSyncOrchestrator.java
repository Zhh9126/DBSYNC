package org.dbdatasync.parser.ddl;

import org.dbdatasync.sdk.enums.DDLOperationEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wave24：表级 DDL 自动同步 + DDL-DML 顺序一致性编排。
 *
 * <p>Wave23 只识别 9 类表级 DDL 并打「需人工确认」标记，既不记录历史、也不执行、
 * 更不保障 DDL 与后续 DML 的相对顺序。本编排器把三件事接进真实链路：</p>
 *
 * <ol>
 *     <li><b>schema history</b>：每个 DDL 事件入 {@link SchemaHistory} 账本（单调序号、状态机、落盘）；</li>
 *     <li><b>自动同步</b>：开关 {@code enableDdlAutoSync} 开启且非高风险类型时，
 *         用连接器 {@code writerDDL} 执行目标 SQL；高风险类型（DROP/TRUNCATE/RENAME/CREATE TABLE）
 *         仍需人工确认，确认时可提供跨方言目标 SQL；</li>
 *     <li><b>顺序守卫</b>：DDL 未确认/未应用前，同表后续 DML 进入有界挂起队列而不写入，
 *         确认应用后按原顺序重放，避免「先按旧 schema 写 DML、后改表」造成数据错乱。</li>
 * </ol>
 *
 * <p>安全边界：自动同步默认关闭；跨源表级 DDL 无机械翻译，状态置为
 * {@code needs_target_sql} 等待人工提供目标 SQL，不拿源端 SQL 直接回放；
 * 挂起队列有界（每表 {@value #MAX_PENDING_DML_PER_TABLE} 条），超限明确告警而非静默丢失。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class DdlSyncOrchestrator {

    /** 目标端 DDL 执行器（由 GeneralBufferActuator 用 connectorFactory.writerDDL 注入） */
    public interface DdlExecutor {
        String apply(String targetSql, String operation) throws Exception;
    }

    public static final int MAX_PENDING_DML_PER_TABLE = 10000;

    private static final Logger logger = LoggerFactory.getLogger(DdlSyncOrchestrator.class);
    private static final DdlSyncOrchestrator INSTANCE = new DdlSyncOrchestrator();

    private final Map<String, DdlExecutor> executors = new ConcurrentHashMap<>();
    private final Map<String, List<Runnable>> pendingDml = new ConcurrentHashMap<>();

    private DdlSyncOrchestrator() {
    }

    public static DdlSyncOrchestrator current() {
        return INSTANCE;
    }

    /** 记录一条表级 DDL；autoSync 开启且类型安全时自动应用。 */
    public SchemaHistory.Entry record(String metaId, String tableName, DDLOperationEnum operation,
                                      String sourceSql, String targetSql, long sourceEventTime,
                                      boolean autoSync, boolean sameDialect, DdlExecutor executor) {
        boolean manualRequired = DDLStatementClassifier.requiresManualConfirmation(operation);
        String effectiveTargetSql = sameDialect ? (targetSql == null ? sourceSql : targetSql) : null;
        SchemaHistory.Entry entry = SchemaHistory.current().append(metaId, tableName,
                operation == null ? "" : operation.name(), sourceSql, effectiveTargetSql,
                sourceEventTime, manualRequired);
        if (executor != null) {
            executors.put(entry.getId(), executor);
        }
        if (autoSync && !manualRequired && effectiveTargetSql != null && !effectiveTargetSql.isEmpty()) {
            confirmAndApply(entry.getId(), "auto", null);
        }
        return entry;
    }

    /** 该表是否存在未处理完的 DDL（顺序守卫判定） */
    public boolean isTableBlocked(String tableName) {
        return SchemaHistory.current().isBlocked(tableName);
    }

    /** 挂起同表 DML；返回 false 表示队列已满，调用方应告警。 */
    public boolean suspendDml(String tableName, Runnable replay) {
        if (tableName == null || replay == null) {
            return false;
        }
        List<Runnable> queue = pendingDml.computeIfAbsent(tableName, k -> new ArrayList<>());
        synchronized (queue) {
            if (queue.size() >= MAX_PENDING_DML_PER_TABLE) {
                logger.error("DDL 未确认导致表 {} 挂起 DML 超过 {} 条，拒绝新事件（不静默丢数）",
                        tableName, MAX_PENDING_DML_PER_TABLE);
                return false;
            }
            queue.add(replay);
        }
        return true;
    }

    /**
     * 人工确认并应用 DDL；成功后按顺序重放挂起的 DML。
     *
     * @param id                history 记录 ID
     * @param operator          操作人
     * @param overrideTargetSql 可选的跨方言目标 SQL
     */
    public Map<String, Object> confirmAndApply(String id, String operator, String overrideTargetSql) {
        Map<String, Object> out = new LinkedHashMap<>();
        SchemaHistory history = SchemaHistory.current();
        out.put("id", id);
        if (!history.confirm(id, operator, overrideTargetSql)) {
            out.put("ok", false);
            out.put("message", "记录不存在或已应用");
            return out;
        }
        SchemaHistory.Entry entry = history.find(id);
        if (entry.getTargetSql().isEmpty()) {
            out.put("ok", false);
            out.put("status", SchemaHistory.STATUS_NEEDS_TARGET_SQL);
            out.put("message", "跨方言表级 DDL 需提供 targetSql 后再应用，未拿源端 SQL 直接回放");
            return out;
        }
        DdlExecutor executor = executors.get(id);
        if (executor == null) {
            history.markFailed(id, "无可用目标端执行器（服务重启后需重新触发该 DDL 或人工执行）");
            out.put("ok", false);
            out.put("message", "无可用执行器");
            return out;
        }
        try {
            String detail = executor.apply(entry.getTargetSql(), entry.getOperation());
            history.markApplied(id, detail == null ? "applied" : detail);
            int replayed = drain(entry.getTableName());
            out.put("ok", true);
            out.put("status", SchemaHistory.STATUS_APPLIED);
            out.put("replayedDml", replayed);
            out.put("message", detail == null ? "applied" : detail);
        } catch (Exception e) {
            history.markFailed(id, String.valueOf(e.getMessage()));
            out.put("ok", false);
            out.put("status", SchemaHistory.STATUS_FAILED);
            out.put("message", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** 按挂起顺序重放 DML；若重放期间再次出现未完成 DDL，重新挂起剩余事件。 */
    private int drain(String tableName) {
        List<Runnable> queue = pendingDml.get(tableName);
        if (queue == null) {
            return 0;
        }
        List<Runnable> batch;
        synchronized (queue) {
            batch = new ArrayList<>(queue);
            queue.clear();
        }
        int replayed = 0;
        for (Runnable task : batch) {
            if (SchemaHistory.current().isBlocked(tableName)) {
                suspendDml(tableName, task);
                continue;
            }
            try {
                task.run();
                replayed++;
            } catch (Exception e) {
                logger.error("replay suspended DML failed for table {}: {}", tableName, e.getMessage());
                suspendDml(tableName, task);
            }
        }
        return replayed;
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = SchemaHistory.current().summary();
        int suspended = 0;
        for (List<Runnable> queue : pendingDml.values()) {
            synchronized (queue) {
                suspended += queue.size();
            }
        }
        out.put("suspendedDml", suspended);
        return out;
    }
}
