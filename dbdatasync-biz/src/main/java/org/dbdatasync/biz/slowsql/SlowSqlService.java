package org.dbdatasync.biz.slowsql;

import org.springframework.stereotype.Component;

import java.util.List;

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
        this.store = new RingBufferSlowSqlStore(t);
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
        store.collect(record);
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

    /** 当前缓冲条数 */
    public int size() {
        return store.size();
    }
}
