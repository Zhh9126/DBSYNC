/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.retry;

import org.dbdatasync.biz.SystemConfigService;
import org.dbdatasync.parser.model.SystemConfig;
import org.dbdatasync.sdk.checkpoint.CheckpointSettings;
import org.dbdatasync.sdk.checkpoint.Checkpoints;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;

/**
 * 把系统配置页的事务型 checkpoint 参数桥接到 sdk 静态安装点。
 *
 * <p>配置保存后即时生效：{@link Checkpoints#current()} 每次都回调本 Provider。
 * 默认关闭，存量部署行为完全不变。</p>
 */
@Component
public class SystemConfigCheckpointProvider implements Checkpoints.Provider {

    @Resource
    private SystemConfigService systemConfigService;

    @PostConstruct
    public void init() {
        Checkpoints.installProvider(this);
    }

    @PreDestroy
    public void destroy() {
        Checkpoints.uninstallProvider();
    }

    @Override
    public CheckpointSettings get() {
        SystemConfig config = systemConfigService.getSystemConfig();
        if (config == null || !config.isEnableTransactionalCheckpoint()) {
            return CheckpointSettings.disabled();
        }
        int maxKeys = config.getCheckpointMaxKeys();
        if (maxKeys <= 0 || maxKeys > 2_000_000) {
            maxKeys = 200000;
        }
        String dir = config.getCheckpointDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = "data/checkpoints";
        }
        return new CheckpointSettings(true, dir, maxKeys);
    }
}
