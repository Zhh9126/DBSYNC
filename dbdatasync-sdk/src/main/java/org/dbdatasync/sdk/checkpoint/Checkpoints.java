package org.dbdatasync.sdk.checkpoint;

import java.io.File;

/**
 * checkpoint 运行期安装点（静态单例 + 懒初始化，零观察者零开销）。
 *
 * <p>业务层在配置变更时调用 {@link #install(CheckpointSettings)}；
 * 主链路只调用 {@link #current()} / {@link #isEnabled()}，不感知 Spring。</p>
 */
public final class Checkpoints {

    /** 配置提供者：业务层注入后，系统配置页保存即时生效 */
    public interface Provider {
        CheckpointSettings get();
    }

    private static volatile CheckpointSettings settings = CheckpointSettings.disabled();
    private static volatile Provider provider;
    private static volatile TransactionalCheckpoint coordinator;
    /** 创建 coordinator 时的配置快照；配置变更（目录/账本上限）后需重建 */
    private static volatile CheckpointSettings coordinatorSettings;
    private static final Object LOCK = new Object();

    private Checkpoints() {
    }

    public static void install(CheckpointSettings newSettings) {
        synchronized (LOCK) {
            settings = newSettings == null ? CheckpointSettings.disabled() : newSettings;
            coordinator = null;
        }
    }

    public static void installProvider(Provider p) {
        synchronized (LOCK) {
            provider = p;
            coordinator = null;
        }
    }

    public static void uninstallProvider() {
        installProvider(null);
    }

    public static CheckpointSettings current() {
        Provider p = provider;
        if (p != null) {
            try {
                CheckpointSettings s = p.get();
                if (s != null) {
                    return s;
                }
            } catch (Throwable ignored) {
                // 配置读取异常时退回静态设置，绝不阻断同步
            }
        }
        return settings;
    }

    public static boolean isEnabled() {
        return current().isEnabled();
    }

    /**
     * 懒初始化：仅在开关打开且首次使用时创建文件与账本。
     * Wave23：系统配置页修改目录或账本上限后，下一次调用自动重建，保证「保存即生效」。
     */
    public static TransactionalCheckpoint coordinator() {
        CheckpointSettings active = current();
        if (!active.isEnabled()) {
            return null;
        }
        TransactionalCheckpoint result = coordinator;
        if (result == null || !sameSettings(coordinatorSettings, active)) {
            synchronized (LOCK) {
                result = coordinator;
                if (result == null || !sameSettings(coordinatorSettings, active)) {
                    File root = active.getRootDir();
                    result = new TransactionalCheckpoint(
                            new FileCheckpointStore(root),
                            new IdempotencyLedger(new File(root, "idempotency.log"),
                                    active.getMaxLedgerEntries()));
                    coordinator = result;
                    coordinatorSettings = active;
                }
            }
        }
        return result;
    }

    private static boolean sameSettings(CheckpointSettings a, CheckpointSettings b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getRootDir().equals(b.getRootDir())
                && a.getMaxLedgerEntries() == b.getMaxLedgerEntries();
    }

    /** 仅供单测重置 */
    public static void reset() {
        synchronized (LOCK) {
            settings = CheckpointSettings.disabled();
            provider = null;
            coordinator = null;
            coordinatorSettings = null;
        }
    }
}
