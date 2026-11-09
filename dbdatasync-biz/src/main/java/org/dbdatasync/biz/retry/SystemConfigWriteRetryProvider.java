/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.retry;

import org.dbdatasync.biz.SystemConfigService;
import org.dbdatasync.parser.model.SystemConfig;
import org.dbdatasync.sdk.retry.WriteRetrySettings;
import org.dbdatasync.sdk.retry.WriteRetrySettingsHolder;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;

/**
 * 把系统配置页的写入重试参数桥接到 sdk 的静态配置安装点。
 *
 * <p>每次读取都取实时配置，用户在「系统配置」页保存后立即生效，无需重启。
 * sdk 主链路只依赖 Holder，不反向依赖 parser/biz 的配置模型。
 *
 * @author wuji
 * @version 1.0.0
 */
@Component
public class SystemConfigWriteRetryProvider implements WriteRetrySettingsHolder.Provider {

    @Resource
    private SystemConfigService systemConfigService;

    @PostConstruct
    public void init() {
        WriteRetrySettingsHolder.install(this);
    }

    @PreDestroy
    public void destroy() {
        WriteRetrySettingsHolder.uninstall();
    }

    @Override
    public WriteRetrySettings get() {
        SystemConfig config = systemConfigService.getSystemConfig();
        if (config == null || !config.isEnableWriteRetry()) {
            return WriteRetrySettings.disabled();
        }
        int maxAttempts = clamp(config.getWriteRetryMaxAttempts(), 1, 10,
                WriteRetrySettings.DEFAULT_MAX_ATTEMPTS);
        long base = clamp(config.getWriteRetryBaseMillis(), 0, 2000,
                (int) WriteRetrySettings.DEFAULT_BASE_MILLIS);
        long budget = clamp(config.getWriteRetryMaxMillis(), 0, 30000,
                (int) WriteRetrySettings.DEFAULT_MAX_ELAPSED_MILLIS);
        long cap = Math.min(Math.max(base, 0L) * 8L, Math.max(budget, 0L));
        if (cap <= 0) {
            cap = WriteRetrySettings.DEFAULT_CAP_MILLIS;
        }
        return new WriteRetrySettings(true, maxAttempts, base, budget, cap);
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            return fallback;
        }
        return value;
    }
}
