/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.retry;

/**
 * 写入重试策略的运行时快照。
 *
 * <p>该对象是不可变值对象，由业务层从 SystemConfig 构造后安装到
 * {@link WriteRetrySettingsHolder}，sdk 主链路只读、不反向依赖业务配置。
 *
 * @author wuji
 * @version 1.0.0
 */
public final class WriteRetrySettings {

    /** 默认：开启，最多重试 3 次，基础退避 50ms，单条总预算 2000ms，单次退避上限 500ms */
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final long DEFAULT_BASE_MILLIS = 50L;
    public static final long DEFAULT_MAX_ELAPSED_MILLIS = 2000L;
    public static final long DEFAULT_CAP_MILLIS = 500L;

    private final boolean enabled;
    private final int maxAttempts;
    private final long baseMillis;
    private final long maxElapsedMillis;
    private final long capMillis;

    public WriteRetrySettings(boolean enabled, int maxAttempts, long baseMillis,
                              long maxElapsedMillis, long capMillis) {
        this.enabled = enabled;
        this.maxAttempts = maxAttempts < 0 ? 0 : maxAttempts;
        this.baseMillis = baseMillis < 0 ? 0L : baseMillis;
        this.maxElapsedMillis = maxElapsedMillis < 0 ? 0L : maxElapsedMillis;
        this.capMillis = capMillis < 0 ? 0L : capMillis;
    }

    public static WriteRetrySettings defaults() {
        return new WriteRetrySettings(DEFAULT_ENABLED, DEFAULT_MAX_ATTEMPTS,
                DEFAULT_BASE_MILLIS, DEFAULT_MAX_ELAPSED_MILLIS, DEFAULT_CAP_MILLIS);
    }

    public static WriteRetrySettings disabled() {
        return new WriteRetrySettings(false, 0, 0L, 0L, 0L);
    }

    public boolean isEnabled() {
        return enabled && maxAttempts > 0 && maxElapsedMillis > 0;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getBaseMillis() {
        return baseMillis;
    }

    public long getMaxElapsedMillis() {
        return maxElapsedMillis;
    }

    public long getCapMillis() {
        return capMillis;
    }
}
