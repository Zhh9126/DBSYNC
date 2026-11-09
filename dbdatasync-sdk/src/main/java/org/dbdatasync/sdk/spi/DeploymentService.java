/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.sdk.spi;

/**
 * 部署服务接口
 *
 * @Version 1.0.0
 * @Author AE86
 * @Date 2023-11-19 23:24
 */
public interface DeploymentService {

    /**
     * Wave23 集群 HA 契约。全部为 default 方法，既有实现（含第三方 SPI）零改动即可编译。
     * 单机实现见 {@code StandaloneProvider}：本地/共享文件租约 + 心跳 + 任务接管。
     */

    /** 本节点稳定标识 */
    default String nodeId() { return "default-node"; }

    /** 当前节点是否持有 leader 租约 */
    default boolean isLeader() { return true; }

    /** 尝试取得某个任务的独占租约；已被其它存活节点持有则返回 false */
    default boolean acquireTaskLease(String metaId) { return true; }

    /** 释放任务租约 */
    default void releaseTaskLease(String metaId) { }

    /**
     * Wave24：读取某任务当前的 fencing token（单调递增，接管后变大）。
     * 未实现 HA 的部署返回 0，表示不启用栅栏。
     */
    default long currentFencingToken(String metaId) { return 0L; }

    /** Wave24：校验持有 token 的旧节点是否仍可写入；默认放行（单机模式） */
    default boolean isFencingTokenValid(String metaId, long token) { return true; }

    /** 扫描并接管过期租约的任务，返回被接管的 metaId 列表 */
    default java.util.List<String> takeoverExpiredTasks() { return java.util.Collections.emptyList(); }

    /** 心跳续约；由调度器周期调用，也允许运维手动触发 */
    default void heartbeat() { }

    /** 集群状态快照，供 /actuator 或运维页读取 */
    default java.util.Map<String, Object> clusterState() {
        java.util.Map<String, Object> state = new java.util.LinkedHashMap<String, Object>();
        state.put("nodeId", nodeId());
        state.put("leader", isLeader());
        state.put("mode", "default");
        return state;
    }

    /** 启动/停止后台心跳（幂等） */
    default void start() { }
    default void stop() { }
}
