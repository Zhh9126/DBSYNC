/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.manager.impl;

import org.dbdatasync.common.enums.CommonTaskStatusEnum;
import org.dbdatasync.manager.ManagerFactory;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.TaskProfile;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.SystemConfig;
import org.dbdatasync.sdk.spi.DeploymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * Wave23 集群 HA：把「过期任务租约」接到真实的同步任务恢复链路。
 *
 * <p>只做编排，不直接起线程：发现过期租约后，通过 {@link MetaProfile} 反查任务级
 * Meta 与 Mapping，再调用 {@link ManagerFactory#start(Mapping, boolean)} 以自动恢复
 * 模式重新启动真实 CDC 监听。若任务本身已不处于运行态，则立即归还租约，
 * 避免把已停止的任务误拉起。</p>
 *
 * <p>默认关闭（系统配置 {@code enableClusterHA}）。关闭时本方法直接返回，无文件 IO。</p>
 *
 * <p>边界：文件租约实现要求多节点共享同一目录（NFS / 共享盘）。
 * 这不是分布式共识算法，无法防护共享存储自身故障与时钟大幅漂移。</p>
 */
@Component
public class ClusterTaskTakeoverOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(ClusterTaskTakeoverOrchestrator.class);

    @Resource
    private DeploymentService deploymentService;

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private TaskProfile taskProfile;

    @Resource
    private ManagerFactory managerFactory;

    @Scheduled(initialDelay = 30_000L, fixedDelay = 30_000L)
    public void takeover() {
        if (!clusterHaEnabled()) {
            return;
        }
        List<String> metaIds;
        try {
            metaIds = deploymentService.takeoverExpiredTasks();
        } catch (Exception e) {
            logger.warn("扫描过期任务租约失败: {}", e.getMessage());
            return;
        }
        if (metaIds == null || metaIds.isEmpty()) {
            return;
        }
        for (String metaId : metaIds) {
            try {
                if (!restart(metaId)) {
                    deploymentService.releaseTaskLease(metaId);
                }
            } catch (Exception e) {
                deploymentService.releaseTaskLease(metaId);
                logger.error("接管任务失败, metaId={}, err={}", metaId, e.getMessage(), e);
            }
        }
    }

    /** @return 是否确已把真实任务重新拉起 */
    private boolean restart(String metaId) {
        Meta meta = metaProfile.getMeta(metaId);
        if (meta == null || meta.getState() != CommonTaskStatusEnum.RUNNING.getCode()) {
            logger.info("任务已非运行态，归还接管租约: {}", metaId);
            return false;
        }
        String taskId = metaProfile.resolveTaskDetailShardId(meta);
        Mapping mapping = taskProfile.getTask(taskId, Mapping.class);
        if (mapping == null) {
            logger.warn("找不到租约对应的任务配置, metaId={}, taskId={}", metaId, taskId);
            return false;
        }
        managerFactory.start(mapping, true);
        logger.info("已接管并恢复真实同步任务: metaId={}, taskId={}, name={}",
                metaId, taskId, mapping.getName());
        return true;
    }

    private boolean clusterHaEnabled() {
        try {
            SystemConfig config = profileComponent.getSystemConfig();
            return config != null && config.isEnableClusterHA();
        } catch (Exception ignored) {
            return false;
        }
    }
}
