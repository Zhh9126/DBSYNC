/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.engine;

/**
 * 同步引擎配置（DataX 式分治模型参数）。
 *
 * <p>核心创新点：通过 {@link #concurrency} 控制并发 TaskGroup 数，
 * 通过 {@link #channelCapacity} 与 {@link #bytesPerSecond} 实现内存有界与流速控制，
 * 通过 {@link #async} 切换同步/异步双模式，通过 {@link #resumePoint} 支持断点续传。</p>
 */
public class EngineConfig {

    /** 并发 TaskGroup 数（默认 CPU 核数） */
    private int concurrency = Math.max(1, Runtime.getRuntime().availableProcessors());

    /** Channel 有界队列容量（reader→writer 解耦缓冲） */
    private int channelCapacity = 1024;

    /** 每批写入条数 */
    private int batchSize = 1000;

    /** 流速上限（字节/秒），<=0 表示不限速 */
    private long bytesPerSecond = 0;

    /** 记录数流速上限（条/秒），<=0 表示不限速 */
    private long recordsPerSecond = 0;

    /** 是否异步模式：true=reader/writer 解耦独立线程；false=同一 Job 线程直连 */
    private boolean async = false;

    /** 分片主键（用于按范围切分 Task） */
    private String splitPk;

    /** 断点续传游标值（null 表示从头部开始） */
    private String resumePoint;

    public EngineConfig() {
    }

    // getter/setter
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public int getChannelCapacity() { return channelCapacity; }
    public void setChannelCapacity(int channelCapacity) { this.channelCapacity = channelCapacity; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public long getBytesPerSecond() { return bytesPerSecond; }
    public void setBytesPerSecond(long bytesPerSecond) { this.bytesPerSecond = bytesPerSecond; }
    public long getRecordsPerSecond() { return recordsPerSecond; }
    public void setRecordsPerSecond(long recordsPerSecond) { this.recordsPerSecond = recordsPerSecond; }
    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }
    public String getSplitPk() { return splitPk; }
    public void setSplitPk(String splitPk) { this.splitPk = splitPk; }
    public String getResumePoint() { return resumePoint; }
    public void setResumePoint(String resumePoint) { this.resumePoint = resumePoint; }
}
