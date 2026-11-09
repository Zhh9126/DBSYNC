package org.dbdatasync.sdk.checkpoint;

import java.io.File;

/**
 * 事务型 checkpoint 配置（默认关闭，保证存量部署零影响）。
 *
 * <p>由业务层从 SystemConfig 安装；sdk 主链路只读取当前设置，不反向依赖 Spring。</p>
 */
public final class CheckpointSettings {

    private final boolean enabled;
    private final File rootDir;
    private final int maxLedgerEntries;

    public CheckpointSettings(boolean enabled, String rootDir, int maxLedgerEntries) {
        this.enabled = enabled;
        this.rootDir = new File(rootDir == null || rootDir.trim().isEmpty()
                ? "data/checkpoints" : rootDir.trim());
        this.maxLedgerEntries = maxLedgerEntries > 0 ? maxLedgerEntries : 200000;
    }

    public static CheckpointSettings disabled() {
        return new CheckpointSettings(false, "data/checkpoints", 200000);
    }

    public boolean isEnabled() { return enabled; }
    public File getRootDir() { return rootDir; }
    public int getMaxLedgerEntries() { return maxLedgerEntries; }
}
