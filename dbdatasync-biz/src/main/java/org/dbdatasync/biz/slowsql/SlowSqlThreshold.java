package org.dbdatasync.biz.slowsql;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 慢 SQL 采集阈值与采样配置。
 *
 * <ul>
 *   <li>{@code thresholdMillis}：慢 SQL 判定阈值（毫秒），默认 1000</li>
 *   <li>{@code maxRecords}：环形缓冲容量（条数），默认 1000</li>
 *   <li>{@code sampleRate}：采样率（0~1），默认 1.0（全采样）</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class SlowSqlThreshold {

    /** 默认慢 SQL 阈值（毫秒） */
    public static final long DEFAULT_THRESHOLD_MILLIS = 1000L;

    /** 默认环形缓冲容量 */
    public static final int DEFAULT_MAX_RECORDS = 1000;

    /** 默认采样率（全采样） */
    public static final double DEFAULT_SAMPLE_RATE = 1.0D;

    /** 慢 SQL 判定阈值（毫秒） */
    private long thresholdMillis = DEFAULT_THRESHOLD_MILLIS;

    /** 环形缓冲容量（条数） */
    private int maxRecords = DEFAULT_MAX_RECORDS;

    /** 采样率（0~1） */
    private double sampleRate = DEFAULT_SAMPLE_RATE;

    /**
     * 是否对本条记录采样：{@code sampleRate >= 1} 恒为 {@code true}，
     * 否则以 {@link ThreadLocalRandom} 生成随机数与采样率比较。
     *
     * @return true=入库，false=丢弃
     */
    public boolean shouldSample() {
        if (sampleRate >= 1.0D) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    public long getThresholdMillis() {
        return thresholdMillis;
    }

    public void setThresholdMillis(long thresholdMillis) {
        this.thresholdMillis = thresholdMillis;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public void setMaxRecords(int maxRecords) {
        this.maxRecords = maxRecords;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        this.sampleRate = sampleRate;
    }
}
