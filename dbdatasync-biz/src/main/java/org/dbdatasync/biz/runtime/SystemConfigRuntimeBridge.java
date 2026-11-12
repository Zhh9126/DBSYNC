/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.runtime;

import org.dbdatasync.biz.SystemConfigService;
import org.dbdatasync.manager.deployment.StandaloneProvider;
import org.dbdatasync.parser.model.SystemConfig;
import org.dbdatasync.sdk.spi.DeploymentService;
import org.dbdatasync.sdk.util.RowIdentityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.File;

/**
 * Wave23：把系统配置页里的运行期开关桥接到 SDK / Manager 的真实执行路径。
 *
 * <p>与 {@code SystemConfigCheckpointProvider} 同样的 Provider 模式：
 * 每次调用都重新读取 SystemConfig，因此配置保存后即时生效，不需要重启服务。</p>
 *
 * <ul>
 *   <li>无主键表退化开关 → {@link RowIdentityResolver#isFallbackEnabled()}，
 *       影响 {@code PrimaryKeyUtil.findTablePrimaryKeys()} 的真实写入链路。</li>
 *   <li>集群租约目录 → {@link StandaloneProvider}，影响 leader / 任务租约文件的真实落盘位置。</li>
 * </ul>
 *
 * <p>配置读取异常一律回落到安全默认值，绝不阻断同步。</p>
 */
@Component
public class SystemConfigRuntimeBridge {

    private static final Logger logger = LoggerFactory.getLogger(SystemConfigRuntimeBridge.class);
    private static final String DEFAULT_CLUSTER_DIR = "data/cluster";

    @Resource
    private SystemConfigService systemConfigService;

    @Resource
    private DeploymentService deploymentService;

    @PostConstruct
    public void init() {
        RowIdentityResolver.installFallbackProvider(() -> {
            SystemConfig config = safeConfig();
            return config == null || config.isEnableNoPkFallback();
        });

        if (deploymentService instanceof StandaloneProvider) {
            ((StandaloneProvider) deploymentService).setLeaseDirProvider(() -> {
                SystemConfig config = safeConfig();
                String dir = config == null ? null : config.getClusterLeaseDir();
                if (dir == null || dir.trim().isEmpty()) {
                    dir = DEFAULT_CLUSTER_DIR;
                }
                return new File(dir.trim());
            });
        }
        // Wave24：目标库事务内位点表开关（配置保存后即时生效）
        org.dbdatasync.sdk.checkpoint.TargetTxCheckpoint.installEnabledProvider(() -> {
            SystemConfig config = safeConfig();
            return config != null && config.isEnableCheckpointInTargetTx()
                    && config.isEnableTransactionalCheckpoint();
        });
        logger.info("Wave23/24 运行期配置桥接已安装: noPkFallback / clusterLeaseDir / targetTxCheckpoint");
    }

    @PreDestroy
    public void destroy() {
        RowIdentityResolver.uninstallFallbackProvider();
    }

    private SystemConfig safeConfig() {
        try {
            return systemConfigService.getSystemConfig();
        } catch (Exception e) {
            logger.debug("读取系统配置失败，使用安全默认值: {}", e.getMessage());
            return null;
        }
    }
}
