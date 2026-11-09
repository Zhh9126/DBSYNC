package org.dbdatasync.biz.slowsql;

import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 慢 SQL 采集服务（Wave17 运行时接线层）。
 *
 * <p>包装 {@link RingBufferSlowSqlStore}，作为 Spring 单例供 web 层（{@code SlowSqlController）}
 * 与引擎侧挂钩共用。运行态接线点：引擎/驱动执行耗时回调 {@link #collect} 即完成采集；
 * 采集口径（阈值/容量/采样率）可通过 {@link #configure} 运行时调整（重建底层环形缓冲）。</p>
 *
 * <p>纯内存实现，不连库、不落表；持久化由调用方按需扩展。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Component("slowSqlService")
public class SlowSqlService {

    /** 默认阈值配置 */
    private volatile SlowSqlThreshold threshold = defaultThreshold();

    /** 底层环形缓冲（配置变更时重建） */
    private volatile RingBufferSlowSqlStore store = new RingBufferSlowSqlStore(threshold);
    /** Wave23：非空时采集记录落盘，重启可恢复 */
    private volatile File persistFile;

    private static SlowSqlThreshold defaultThreshold() {
        SlowSqlThreshold t = new SlowSqlThreshold();
        t.setThresholdMillis(SlowSqlThreshold.DEFAULT_THRESHOLD_MILLIS);
        t.setMaxRecords(SlowSqlThreshold.DEFAULT_MAX_RECORDS);
        t.setSampleRate(SlowSqlThreshold.DEFAULT_SAMPLE_RATE);
        return t;
    }

    /**
     * 运行时调整采集口径（阈值/容量/采样率）；非法值回退默认。重建底层缓冲（历史清空）。
     */
    public synchronized void configure(Long thresholdMillis, Integer maxRecords, Double sampleRate) {
        SlowSqlThreshold t = new SlowSqlThreshold();
        t.setThresholdMillis(thresholdMillis != null && thresholdMillis > 0
                ? thresholdMillis : SlowSqlThreshold.DEFAULT_THRESHOLD_MILLIS);
        t.setMaxRecords(maxRecords != null && maxRecords > 0
                ? maxRecords : SlowSqlThreshold.DEFAULT_MAX_RECORDS);
        t.setSampleRate(sampleRate != null && sampleRate >= 0 && sampleRate <= 1
                ? sampleRate : SlowSqlThreshold.DEFAULT_SAMPLE_RATE);
        this.threshold = t;
        this.store = persistFile == null
                ? new RingBufferSlowSqlStore(t) : new PersistentSlowSqlStore(t, persistFile);
    }

    /**
     * Wave23：启用慢 SQL 持久化（收口 Wave17「纯内存重启丢失」）。
     * 切换后回放历史记录，后续采集同步追加落盘。
     */
    public synchronized void enablePersistence(String rootDir) {
        if (rootDir == null || rootDir.trim().isEmpty()) {
            return;
        }
        this.persistFile = new File(rootDir.trim(), "slow-sql.log");
        this.store = new PersistentSlowSqlStore(threshold, persistFile);
    }

    public String persistenceType() {
        return store instanceof PersistentSlowSqlStore ? "file" : "memory";
    }

    /**
     * 采集一条 SQL 执行记录（引擎/驱动挂钩调用点）。
     *
     * @param sql           SQL 文本
     * @param source        来源标识（任务/连接器）
     * @param executeMillis 执行耗时（毫秒）
     * @param rows          影响行数
     */
    public void collect(String sql, String source, long executeMillis, long rows) {
        SlowSqlRecord record = new SlowSqlRecord(sql, source, executeMillis, rows,
                System.currentTimeMillis(), false);
        // Wave24：引擎观察事件把当前任务 ID 拼在 source 尾部，落到记录上供按任务聚合/过滤
        record.setTaskId(extractTaskId(source));
        store.collect(record);
    }

    /** source 形如 {@code observer:SimpleConnection|task=T123}；无任务上下文返回 null。 */
    static String extractTaskId(String source) {
        if (source == null) {
            return null;
        }
        int index = source.indexOf("|task=");
        if (index < 0) {
            return null;
        }
        String taskId = source.substring(index + "|task=".length()).trim();
        return taskId.isEmpty() ? null : taskId;
    }

    /** 当前快照（只读副本，按存入顺序） */
    public List<SlowSqlRecord> records() {
        return store.snapshot();
    }

    /**
     * 统计报告。
     *
     * @param thresholdMillis 慢 SQL 阈值；&le;0 时用当前配置阈值
     * @param topN            最慢 Top N
     */
    public SlowSqlReport report(long thresholdMillis, int topN) {
        long effective = thresholdMillis > 0 ? thresholdMillis : threshold.getThresholdMillis();
        return SlowSqlAnalyzer.analyze(store.snapshot(), effective, topN);
    }

    /** 当前采集口径 */
    public SlowSqlThreshold getThreshold() {
        return threshold;
    }

    /**
     * Wave24：按同步任务聚合慢 SQL（任务级上下文）。无任务上下文的记录归入 {@code <unknown>}。
     */
    public Map<String, Map<String, Object>> taskReport() {
        Map<String, List<Long>> grouped = new LinkedHashMap<>();
        for (SlowSqlRecord record : store.snapshot()) {
            String taskId = record.getTaskId() == null || record.getTaskId().isEmpty()
                    ? "<unknown>" : record.getTaskId();
            grouped.computeIfAbsent(taskId, key -> new ArrayList<>()).add(record.getExecuteMillis());
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        grouped.forEach((taskId, times) -> {
            Collections.sort(times);
            long sum = 0L;
            for (Long millis : times) {
                sum += millis;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("count", times.size());
            item.put("avgMillis", times.isEmpty() ? 0L : sum / times.size());
            item.put("maxMillis", times.isEmpty() ? 0L : times.get(times.size() - 1));
            item.put("p95Millis", times.isEmpty() ? 0L
                    : times.get(Math.max(0, (int) Math.ceil(0.95d * times.size()) - 1)));
            result.put(taskId, item);
        });
        return result;
    }

    /** 当前缓冲条数 */
    public int size() {
        return store.size();
    }
}
