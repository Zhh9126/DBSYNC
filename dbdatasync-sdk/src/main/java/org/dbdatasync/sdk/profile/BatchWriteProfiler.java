/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave24：大事务批写性能剖析（进程内、零依赖、默认开启的低开销观测点）。
 *
 * <p>此前引擎只有 TPS 计数，无法回答「大事务批写慢在哪里」：没有批次行数分布、
 * 没有 p95/p99、没有慢批次数。本剖析器在 {@code GeneralBufferActuator} 的
 * 批量写入前后采样，保留最近 {@value #CAPACITY} 个批次的耗时快照，
 * 输出 p50/p95/p99、行数分桶与吞吐，供性能剖析与容量评估。</p>
 *
 * <p>设计约束：有界环形缓冲（不会随运行时长增长）、synchronized 短临界区、
 * 零第三方依赖、纯算法可离线单测；统计快照是只读副本。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class BatchWriteProfiler {

    public static final int CAPACITY = 4096;
    /** 超过该耗时（毫秒）计为慢批次 */
    public static final long SLOW_BATCH_MILLIS = 1000L;

    private static final BatchWriteProfiler INSTANCE = new BatchWriteProfiler();

    private final long[] elapsed = new long[CAPACITY];
    private final int[] rows = new int[CAPACITY];
    private int cursor;
    private int size;
    private long totalBatches;
    private long totalRows;
    private long totalElapsedMillis;
    private long slowBatches;

    private BatchWriteProfiler() {
    }

    public static BatchWriteProfiler instance() {
        return INSTANCE;
    }

    /** 记录一次批量写入；rows &le; 0 或耗时为负时忽略。 */
    public synchronized void record(int rowCount, long elapsedMillis) {
        if (rowCount <= 0 || elapsedMillis < 0) {
            return;
        }
        elapsed[cursor] = elapsedMillis;
        rows[cursor] = rowCount;
        cursor = (cursor + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
        totalBatches++;
        totalRows += rowCount;
        totalElapsedMillis += elapsedMillis;
        if (elapsedMillis >= SLOW_BATCH_MILLIS) {
            slowBatches++;
        }
    }

    /** 统计快照：p50/p95/p99、行数分桶、累计吞吐。 */
    public synchronized Map<String, Object> snapshot() {
        List<Long> samples = new ArrayList<>(size);
        Map<String, Long> buckets = new LinkedHashMap<>();
        buckets.put("1-100", 0L);
        buckets.put("101-1000", 0L);
        buckets.put("1001-10000", 0L);
        buckets.put("10000+", 0L);
        for (int i = 0; i < size; i++) {
            int idx = (cursor - size + i + CAPACITY) % CAPACITY;
            samples.add(elapsed[idx]);
            int rowCount = rows[idx];
            String key = rowCount <= 100 ? "1-100"
                    : rowCount <= 1000 ? "101-1000"
                    : rowCount <= 10000 ? "1001-10000" : "10000+";
            buckets.put(key, buckets.get(key) + 1L);
        }
        Collections.sort(samples);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleSize", size);
        report.put("capacity", CAPACITY);
        report.put("totalBatches", totalBatches);
        report.put("totalRows", totalRows);
        report.put("totalElapsedMillis", totalElapsedMillis);
        report.put("avgMillis", totalBatches == 0 ? 0L : totalElapsedMillis / totalBatches);
        report.put("p50Millis", percentile(samples, 0.50d));
        report.put("p95Millis", percentile(samples, 0.95d));
        report.put("p99Millis", percentile(samples, 0.99d));
        report.put("maxMillis", samples.isEmpty() ? 0L : samples.get(samples.size() - 1));
        report.put("slowBatches", slowBatches);
        report.put("slowBatchThresholdMillis", SLOW_BATCH_MILLIS);
        report.put("rowsPerSecond", totalElapsedMillis == 0 ? 0L : totalRows * 1000L / totalElapsedMillis);
        report.put("rowBuckets", buckets);
        return report;
    }

    private static long percentile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    public synchronized void reset() {
        cursor = 0;
        size = 0;
        totalBatches = 0;
        totalRows = 0;
        totalElapsedMillis = 0;
        slowBatches = 0;
    }
}
