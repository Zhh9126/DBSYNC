package org.dbdatasync.manager;

import org.dbdatasync.common.enums.CommonTaskStatusEnum;
import org.dbdatasync.manager.event.ClosedEvent;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.SystemConfig;
import org.dbdatasync.sdk.spi.DeploymentService;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Map;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2019/9/16 23:59
 */
@Component
public class ManagerFactory implements ApplicationListener<ClosedEvent> {

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private Map<String, Puller> map;

    /** Wave23：集群租约服务。默认实现为空操作/单机文件租约，第三方 SPI 使用 default 方法同样安全。 */
    @Resource
    private DeploymentService deploymentService;

    @Override
    public void onApplicationEvent(ClosedEvent event) {
        String metaId = event.getMetaId();
        changeMetaState(metaId, CommonTaskStatusEnum.READY);
        // 任务异常自行关闭时同样要归还租约，否则其它节点要等 TTL 过期才能接管
        if (clusterHaEnabled()) {
            deploymentService.releaseTaskLease(metaId);
        }
        org.dbdatasync.sdk.spi.FencingTokens.unregister(metaId);
    }

    /** Wave24：把租约服务安装到写入栅栏（未启用 HA 时 isFencingTokenValid 默认放行） */
    @javax.annotation.PostConstruct
    public void initFencing() {
        org.dbdatasync.sdk.spi.FencingTokens.install(deploymentService);
    }

    /**
     * Wave23：HA 开关来自系统配置页，保存后即时生效；默认关闭，存量部署行为不变。
     * 配置读取异常时一律按关闭处理，绝不阻断同步任务。
     */
    private boolean clusterHaEnabled() {
        try {
            SystemConfig config = profileComponent.getSystemConfig();
            return config != null && config.isEnableClusterHA();
        } catch (Exception ignored) {
            return false;
        }
    }

    public void start(Mapping mapping) {
        start(mapping, false);
    }

    /**
     * 启动驱动。
     *
     * @param mapping      驱动
     * @param autoRecovery 是否为服务重启自动恢复（true 时对 CDC 监听启动失败按配置重试）
     */
    public void start(Mapping mapping, boolean autoRecovery) {
        Puller puller = getPuller(mapping);
        final String metaId = mapping.getMetaId();
        final boolean ha = clusterHaEnabled();

        // Wave23 集群 HA：只有 leader 才能启动任务，且同一任务在集群内只能有一个持有者。
        // 放在状态标记之前，抢占失败时不污染 Meta 状态，也不启动 CDC 监听。
        if (ha) {
            if (!deploymentService.isLeader()) {
                throw new ManagerException("当前节点不是 Leader，任务启动已拒绝: " + mapping.getName());
            }
            if (!deploymentService.acquireTaskLease(metaId)) {
                throw new ManagerException("任务已被其它存活节点持有，拒绝重复启动: " + mapping.getName());
            }
            // Wave24：登记 fencing token，旧节点恢复后写入前会被栅栏拒绝
            org.dbdatasync.sdk.spi.FencingTokens.register(metaId,
                    deploymentService.currentFencingToken(metaId));
        }

        // 标记运行中
        changeMetaState(metaId, CommonTaskStatusEnum.RUNNING);

        try {
            puller.start(mapping, autoRecovery);
        } catch (Exception e) {
            // rollback
            changeMetaState(metaId, CommonTaskStatusEnum.READY);
            if (ha) {
                deploymentService.releaseTaskLease(metaId);
            }
            org.dbdatasync.sdk.spi.FencingTokens.unregister(metaId);
            throw new ManagerException(e.getMessage());
        }
    }

    public void close(Mapping mapping) {
        Puller puller = getPuller(mapping);

        // 标记停止中
        String metaId = mapping.getMetaId();
        changeMetaState(metaId, CommonTaskStatusEnum.STOPPING);

        puller.close(metaId);
        if (clusterHaEnabled()) {
            deploymentService.releaseTaskLease(metaId);
        }
        org.dbdatasync.sdk.spi.FencingTokens.unregister(metaId);
    }

    public void changeMetaState(String metaId, CommonTaskStatusEnum status) {
        Meta meta = metaProfile.getMeta(metaId);
        int code = status.getCode();
        if (null != meta && meta.getState() != code) {
            meta.setState(code);
            meta.setUpdateTime(Instant.now().toEpochMilli());
            profileComponent.editConfigModel(meta);
        }
    }

    private Puller getPuller(Mapping mapping) {
        Assert.notNull(mapping, "驱动不能为空");
        String model = mapping.getModel();
        String metaId = mapping.getMetaId();
        Assert.hasText(model, "同步方式不能为空");
        Assert.hasText(metaId, "任务ID不能为空");

        Puller puller = map.get(model.concat("Puller"));
        Assert.notNull(puller, String.format("未知的同步方式: %s", model));
        return puller;
    }
}
