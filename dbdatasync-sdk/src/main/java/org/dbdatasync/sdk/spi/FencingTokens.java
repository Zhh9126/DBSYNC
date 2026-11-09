/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wave24：HA fencing token（租约隔离栅栏）。
 *
 * <p>Wave23 的文件租约只有一个 TTL：旧 leader 因 GC / 网络分区暂停后，租约过期被新节点
 * 接管，旧节点恢复时若没检查租约仍可能继续写入，形成双写。fencing token 是每次取得
 * 租约时单调递增的序号：新节点接管后 token 变大，旧节点持有的 token 立即失效，
 * 写入前调用 {@link #isValid} 即可拒绝陈旧节点。</p>
 *
 * <p>默认零影响：未安装 DeploymentService 或未登记 token 的任务一律放行（单机模式）。
 * 这是工程级的「防脑裂双写」保障，仍不是 ZooKeeper/etcd 那样的强一致分布式共识：
 * 租约本身依赖共享文件（NFS/共享盘）与时钟，边界在手册中明示。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class FencingTokens {

    private static final Map<String, Long> TOKENS = new ConcurrentHashMap<>();
    private static volatile DeploymentService service;

    private FencingTokens() {
    }

    public static void install(DeploymentService deploymentService) {
        service = deploymentService;
    }

    public static void register(String metaId, long token) {
        if (metaId != null) {
            TOKENS.put(metaId, token);
        }
    }

    public static void unregister(String metaId) {
        if (metaId != null) {
            TOKENS.remove(metaId);
        }
    }

    /** 未启用 HA 时放行；启用后必须与租约文件里的当前 token 一致且未过期。 */
    public static boolean isValid(String metaId) {
        Long token = metaId == null ? null : TOKENS.get(metaId);
        DeploymentService current = service;
        return token == null || current == null || current.isFencingTokenValid(metaId, token);
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("installed", service != null);
        view.put("tokens", new LinkedHashMap<>(TOKENS));
        return view;
    }
}
