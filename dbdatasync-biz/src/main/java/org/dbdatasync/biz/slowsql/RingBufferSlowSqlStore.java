package org.dbdatasync.biz.slowsql;

import java.util.ArrayList;
import java.util.List;

/**
 * 环形缓冲慢 SQL 存储实现。
 *
 * <p>容量取自 {@link SlowSqlThreshold#getMaxRecords()}，满后覆盖最旧记录；
 * {@code collect} / {@code snapshot} 均以 {@code synchronized} 保证线程安全。
 * 入库前统一依据阈值标记 {@code thresholdExceeded}（{@code executeMillis >= thresholdMillis}），
 * 并按 {@link SlowSqlThreshold#shouldSample()} 决定是否入库。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class RingBufferSlowSqlStore implements SlowSqlCollector {

    /** 采集配置（阈值 / 容量 / 采样率） */
    private final SlowSqlThreshold threshold;

    /** 环形缓冲区 */
    private final SlowSqlRecord[] buffer;

    /** 下一个写入位置 */
    private int writeIndex;

    /** 当前已存条数 */
    private int size;

    /**
     * 以默认配置构建存储。
     */
    public RingBufferSlowSqlStore() {
        this(new SlowSqlThreshold());
    }

    /**
     * 以指定配置构建存储。
     *
     * @param threshold 采集配置，不可为 null
     */
    public RingBufferSlowSqlStore(SlowSqlThreshold threshold) {
        if (threshold == null) {
            throw new IllegalArgumentException("threshold must not be null");
        }
        int maxRecords = threshold.getMaxRecords();
        if (maxRecords <= 0) {
            throw new IllegalArgumentException("maxRecords must be positive: " + maxRecords);
        }
        this.threshold = threshold;
        this.buffer = new SlowSqlRecord[maxRecords];
        this.writeIndex = 0;
        this.size = 0;
    }

    @Override
    public synchronized void collect(SlowSqlRecord record) {
        if (record == null) {
            return;
        }
        // 统一标记是否超过慢 SQL 阈值
        record.setThresholdExceeded(
                record.getExecuteMillis() >= threshold.getThresholdMillis());
        // 采样过滤
        if (!threshold.shouldSample()) {
            return;
        }
        if (size < buffer.length) {
            buffer[writeIndex] = record;
            size++;
        } else {
            // 已满，覆盖最旧记录（writeIndex 即最旧位置）
            buffer[writeIndex] = record;
        }
        writeIndex = (writeIndex + 1) % buffer.length;
    }

    @Override
    public synchronized List<SlowSqlRecord> snapshot() {
        List<SlowSqlRecord> result = new ArrayList<SlowSqlRecord>(size);
        // 缓冲未满时从 0 开始；已满时最旧记录位于 writeIndex，从其开始环绕输出
        int start = (size < buffer.length) ? 0 : writeIndex;
        for (int i = 0; i < size; i++) {
            result.add(buffer[(start + i) % buffer.length]);
        }
        return result;
    }

    /**
     * 当前存储条数。
     *
     * @return 条数（不超过容量）
     */
    public synchronized int size() {
        return size;
    }

    /**
     * 只读暴露采集配置。
     *
     * @return 采集配置
     */
    public SlowSqlThreshold getThreshold() {
        return threshold;
    }
}
