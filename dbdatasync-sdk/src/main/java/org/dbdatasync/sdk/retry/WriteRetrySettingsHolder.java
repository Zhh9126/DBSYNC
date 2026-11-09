/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.retry;

/**
 * 写入重试配置的静态安装点。
 *
 * <p>sdk 层不能依赖 parser/biz 的 SystemConfig，因此由业务层在 Spring 启动时
 * 安装一个 {@link WriteRetrySettingsProvider}；未安装时返回保守默认值。
 * 配置变更通过 provider 实时读取，无需重启。
 *
 * @author wuji
 * @version 1.0.0
 */
public final class WriteRetrySettingsHolder {

    /** 配置提供者 */
    public interface Provider {
        WriteRetrySettings get();
    }

    private static volatile Provider provider;

    private WriteRetrySettingsHolder() {
    }

    public static void install(Provider p) {
        provider = p;
    }

    public static void uninstall() {
        provider = null;
    }

    public static Provider current() {
        return provider;
    }

    /**
     * 读取当前配置。provider 抛异常时回退到默认值，绝不影响同步主链路。
     */
    public static WriteRetrySettings settings() {
        Provider p = provider;
        if (p == null) {
            return WriteRetrySettings.defaults();
        }
        try {
            WriteRetrySettings s = p.get();
            return s == null ? WriteRetrySettings.defaults() : s;
        } catch (Throwable ignored) {
            return WriteRetrySettings.defaults();
        }
    }
}
