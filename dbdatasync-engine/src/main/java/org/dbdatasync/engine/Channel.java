/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.engine;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * DataX 式 Channel：reader 与 writer 之间的有界内存通道。
 *
 * <p>职责：
 * 1. 解耦 reader/writer（支持异步双模式）；
 * 2. 通过有界队列实现背压（reader 满则阻塞，防止 OOM）；
 * 3. 内置令牌桶限速（按记录数/字节数），实现 {@link EngineConfig#getRecordsPerSecond()} 与 bytesPerSecond 控制。</p>
 *
 * <p>这是本项目相对传统单线程流式同步的核心创新点之一：将"抽取"与"写入"解耦为生产者/消费者，
 * 既支持同步（同一 Job 线程内直连消费）也支持异步（独立 WriterRunner 消费），并具备可限流、可中断、可续传的能力。</p>
 */
public class Channel {

    private final BlockingQueue<Record> queue;
    private final long recordsPerSecond;
    private final long bytesPerSecond;

    /** 令牌桶状态 */
    private double recordTokens;
    private double byteTokens;
    private long lastRefillNanos;

    private volatile boolean closed = false;

    public Channel(int capacity, long recordsPerSecond, long bytesPerSecond) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.recordsPerSecond = recordsPerSecond;
        this.bytesPerSecond = bytesPerSecond;
        this.recordTokens = recordsPerSecond > 0 ? recordsPerSecond : 0;
        this.byteTokens = bytesPerSecond > 0 ? bytesPerSecond : 0;
        this.lastRefillNanos = System.nanoTime();
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec <= 0) return;
        lastRefillNanos = now;
        if (recordsPerSecond > 0) {
            recordTokens = Math.min(recordsPerSecond, recordTokens + elapsedSec * recordsPerSecond);
        }
        if (bytesPerSecond > 0) {
            byteTokens = Math.min(bytesPerSecond, byteTokens + elapsedSec * bytesPerSecond);
        }
    }

    /**
     * 生产者（reader）推送一条记录，受限速与背压约束。
     */
    public void push(Record record) throws InterruptedException {
        if (recordsPerSecond > 0 || bytesPerSecond > 0) {
            refill();
            long needBytes = record.estimateBytes();
            while ((recordsPerSecond > 0 && recordTokens < 1)
                    || (bytesPerSecond > 0 && byteTokens < needBytes)) {
                Thread.sleep(1);
                refill();
            }
            if (recordsPerSecond > 0) recordTokens -= 1;
            if (bytesPerSecond > 0) byteTokens -= needBytes;
        }
        queue.put(record);
    }

    /**
     * 消费者（writer）拉取一条记录，通道关闭且为空时返回 null。
     */
    public Record pull(long timeoutMillis) throws InterruptedException {
        if (closed && queue.isEmpty()) {
            return null;
        }
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public int size() {
        return queue.size();
    }

    public void close() {
        this.closed = true;
    }

    public boolean isClosed() {
        return closed;
    }
}
