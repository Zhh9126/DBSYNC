/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.parser.util;

import org.dbdatasync.sdk.constant.ConfigConstant;

import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * 批次级迁移限速器：按「每秒记录数 / 每秒字节数」双维度对全量同步批次做平滑限流。
 *
 * <p>复刻 {@code dbdatasync-engine} 模块 {@code Channel} 的令牌桶语义（按速率补充令牌、
 * 字节桶容量取 {@code max(bytesPerSecond, needBytes)} 防止单条大记录永久阻塞），
 * 采用标准令牌桶：令牌按经过时间以「配置的速率」补充，绝不按时间窗清零。
 * <b>因 engine 模块的 Channel/EngineConfig/SyncEngine 是孤立副本、运行时主链路完全不经过它，
 * 故在此复刻语义、不引入 engine 依赖。</b></p>
 *
 * <p><b>容量与速率分离：</b>桶容量取 {@code max(rate, 本批量)}，只用于「允许单批凑够令牌、
 * 防永久死锁」，不参与补充速率；补充量恒为 {@code elapsedSec * rate}。因此放行一批
 * {@code records} 行约需 {@code records / rate} 秒，长期平均速率约等于配置值，
 * 不会被批次大小架空。</p>
 *
 * <p><b>默认 0 不限速、存量任务零影响：</b>两个速率均 &lt;=0 时 {@link #acquire(int, long)}
 * 在第一个守卫处立即返回，不进入 synchronized、不读取时钟、不 park、不改变任何执行顺序。</p>
 *
 * <p>线程安全：实例可被同一迁移任务的多个分片线程共享，令牌状态由 synchronized 保护。</p>
 */
public final class MigrationRateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    /** 单次 park 上限 1 秒：醒来重新补充令牌并计算剩余等待，兼顾虚假唤醒 */
    private static final long MAX_PARK_NANOS = 1_000_000_000L;
    /** 单次 park 下限 1ms，避免极小等待量导致忙循环 */
    private static final long MIN_PARK_NANOS = 1_000_000L;

    /** 每秒记录数限额，&lt;=0 表示该维度不限速 */
    private final long recordsPerSecond;
    /** 每秒字节数限额，&lt;=0 表示该维度不限速 */
    private final long bytesPerSecond;

    // 以下令牌状态仅在 synchronized(this) 内访问
    private double recordTokens;
    private double byteTokens;
    private long lastRefillNanos;

    public MigrationRateLimiter(long recordsPerSecond, long bytesPerSecond) {
        this.recordsPerSecond = recordsPerSecond;
        this.bytesPerSecond = bytesPerSecond;
        // 初始令牌给 1 秒量，避免首批无谓长等待；未启用维度不需要令牌
        this.recordTokens = recordsPerSecond > 0 ? recordsPerSecond : 0;
        this.byteTokens = bytesPerSecond > 0 ? bytesPerSecond : 0;
        this.lastRefillNanos = System.nanoTime();
    }

    /** 任一速率 &gt;0 即启用限速。 */
    public static boolean isEnabled(long recordsPerSecond, long bytesPerSecond) {
        return recordsPerSecond > 0 || bytesPerSecond > 0;
    }

    public boolean isEnabled() {
        return recordsPerSecond > 0 || bytesPerSecond > 0;
    }

    /**
     * 申请一批数据的配额：读取后、写入前调用。
     *
     * <p>标准令牌桶：每次循环按 {@code elapsedSec * rate} 补充令牌（不清零），
     * 容量取 {@code Math.max(rate, 本批量)} 仅用于允许单批通过、防永久死锁；
     * 令牌足够则扣减并返回，否则按两维度各自缺口计算所需等待时间、取较大者单次
     * park（上限 1 秒），醒来重新补充并重算。被中断时恢复中断标志并立即返回
     * （不抛异常、不阻断迁移）。</p>
     *
     * @param records 本批记录数
     * @param bytes   本批估算字节数
     */
    public void acquire(int records, long bytes) {
        if (!isEnabled()) {
            // 默认 0：零开销、零行为变化
            return;
        }
        if (records <= 0 && bytes <= 0) {
            return;
        }
        synchronized (this) {
            for (;;) {
                long now = System.nanoTime();
                double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
                lastRefillNanos = now;
                // 按「配置速率」补充令牌；容量取 max 仅决定单批能否凑够，不参与补充速率
                if (recordsPerSecond > 0) {
                    double capacity = Math.max((double) recordsPerSecond, (double) records);
                    recordTokens = Math.min(capacity, recordTokens + elapsedSec * recordsPerSecond);
                }
                if (bytesPerSecond > 0) {
                    double capacity = Math.max((double) bytesPerSecond, (double) bytes);
                    byteTokens = Math.min(capacity, byteTokens + elapsedSec * bytesPerSecond);
                }
                boolean recordOk = recordsPerSecond <= 0 || recordTokens >= records;
                boolean byteOk = bytesPerSecond <= 0 || byteTokens >= bytes;
                if (recordOk && byteOk) {
                    if (recordsPerSecond > 0) {
                        recordTokens -= records;
                    }
                    if (bytesPerSecond > 0) {
                        byteTokens -= bytes;
                    }
                    return;
                }
                // 仅对「已启用且令牌不足」的维度计算等待，取较严格（较久）者
                double waitSec = 0;
                if (recordsPerSecond > 0 && recordTokens < records) {
                    waitSec = Math.max(waitSec, (records - recordTokens) / recordsPerSecond);
                }
                if (bytesPerSecond > 0 && byteTokens < bytes) {
                    waitSec = Math.max(waitSec, (bytes - byteTokens) / bytesPerSecond);
                }
                long waitNanos = (long) (waitSec * NANOS_PER_SECOND);
                waitNanos = Math.min(waitNanos, MAX_PARK_NANOS);
                waitNanos = Math.max(waitNanos, MIN_PARK_NANOS);
                LockSupport.parkNanos(waitNanos);
                if (Thread.currentThread().isInterrupted()) {
                    // 恢复中断标志并放弃本次限速等待，不阻断迁移
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public long getRecordsPerSecond() {
        return recordsPerSecond;
    }

    public long getBytesPerSecond() {
        return bytesPerSecond;
    }

    /**
     * 从 Mapping.params 构造：读取 {@code ConfigConstant.RATE_RECORDS_PER_SECOND} 与
     * {@code ConfigConstant.RATE_BYTES_PER_SECOND}；非法 / 空白 / &lt;=0 一律归 0（不限速）。
     */
    public static MigrationRateLimiter fromParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return new MigrationRateLimiter(0, 0);
        }
        return new MigrationRateLimiter(
                parseRate(params.get(ConfigConstant.RATE_RECORDS_PER_SECOND)),
                parseRate(params.get(ConfigConstant.RATE_BYTES_PER_SECOND)));
    }

    private static long parseRate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            long v = Long.parseLong(value.trim());
            return v > 0 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
