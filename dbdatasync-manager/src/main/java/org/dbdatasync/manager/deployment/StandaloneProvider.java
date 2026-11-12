/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.manager.deployment;

import org.dbdatasync.sdk.spi.DeploymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 单机部署
 *
 * @Version 1.0.0
 * @Author AE86
 * @Date 2023-11-19 23:29
 */
public final class StandaloneProvider implements DeploymentService {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneProvider.class);

    /** 租约目录：默认本地 data/cluster；多节点部署时指向共享存储（NFS/共享盘） */
    private static final String DIR_PROPERTY = "dbdatasync.cluster.dir";
    private static final long DEFAULT_TTL_MILLIS = 15000L;
    private static final long DEFAULT_HEARTBEAT_MILLIS = 5000L;

    private final File leaseDir;
    /** Wave23：系统配置页的「集群租约目录」动态来源，保存后下一次租约读写即生效 */
    private volatile Supplier<File> leaseDirProvider;
    private final String nodeId;
    private final long ttlMillis;
    private final long heartbeatMillis;
    /** 懒创建：未启动的实例不占用后台线程；stop 后释放，可再次 start */
    private volatile ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, Long> ownedTasks = new ConcurrentHashMap<String, Long>();
    /** Wave24：本节点持有的 fencing token 与全局单调序号（接管后必须递增） */
    private final ConcurrentHashMap<String, Long> ownedTokens = new ConcurrentHashMap<String, Long>();
    private final java.util.concurrent.atomic.AtomicLong monotonicToken = new java.util.concurrent.atomic.AtomicLong();

    private volatile String leaderNodeId;
    private volatile long leaderLeaseUntil;
    private volatile long heartbeatCount;
    private volatile boolean started;

    public StandaloneProvider() {
        this(new File(System.getProperty(DIR_PROPERTY, "data/cluster")),
                DEFAULT_TTL_MILLIS, DEFAULT_HEARTBEAT_MILLIS);
    }

    public StandaloneProvider(File leaseDir, long ttlMillis, long heartbeatMillis) {
        this.leaseDir = leaseDir;
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MILLIS;
        this.heartbeatMillis = heartbeatMillis > 0 ? heartbeatMillis : DEFAULT_HEARTBEAT_MILLIS;
        if (!leaseDir.exists() && !leaseDir.mkdirs() && !leaseDir.isDirectory()) {
            throw new IllegalStateException("cannot create cluster lease dir: " + leaseDir);
        }
        this.nodeId = resolveNodeId();
        // Wave23：心跳线程池延迟到 start() 才创建。仅仅构造 HA 实例（例如配置探测、
        // 单测里模拟多节点）不应拉起后台线程、占用原生线程栈与提交内存。
        this.scheduler = null;
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    /** 由业务层安装动态租约目录来源；未安装时使用构造时的目录 */
    public void setLeaseDirProvider(Supplier<File> provider) {
        this.leaseDirProvider = provider;
    }

    private File leaseDir() {
        Supplier<File> provider = leaseDirProvider;
        File dir = leaseDir;
        if (provider != null) {
            try {
                File configured = provider.get();
                if (configured != null) {
                    dir = configured;
                }
            } catch (Exception e) {
                logger.warn("cluster lease dir provider failed: {}", e.getMessage());
            }
        }
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("cannot create cluster lease dir: " + dir);
        }
        return dir;
    }

    /** 延迟启动：等待系统配置桥接安装完成，避免用默认目录抢到 leader 后目录漂移 */
    private void ensureStarted() {
        if (!started) {
            start();
        }
    }

    /** 懒创建心跳线程池；daemon 线程保证单测 fork 即使未显式 stop 也能正常退出 */
    private synchronized ScheduledExecutorService scheduler() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "dbdatasync-cluster-heartbeat");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return scheduler;
    }

    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        tryElectLeader();
        scheduler().scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    heartbeat();
                } catch (Exception e) {
                    logger.warn("cluster heartbeat failed: {}", e.getMessage());
                }
            }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        logger.info("cluster node started: id={}, leader={}, dir={}", nodeId, isLeader(), leaseDir().getAbsolutePath());
    }

    @Override
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        ScheduledExecutorService active = scheduler;
        scheduler = null;
        if (active != null) {
            active.shutdownNow();
        }
        if (nodeId.equals(leaderNodeId)) {
            new File(leaseDir(), "leader.lease").delete();
        }
        for (String metaId : ownedTasks.keySet()) {
            releaseTaskLease(metaId);
        }
        logger.info("cluster node stopped: id={}", nodeId);
    }

    @Override
    public void heartbeat() {
        ensureStarted();
        heartbeatCount++;
        long now = System.currentTimeMillis();
        if (nodeId.equals(leaderNodeId) && now < leaderLeaseUntil) {
            // 续约自己
            if (writeLease(new File(leaseDir(), "leader.lease"), nodeId, now + ttlMillis)) {
                leaderLeaseUntil = now + ttlMillis;
            } else {
                leaderNodeId = null;
            }
        } else {
            tryElectLeader();
        }
        // 续约自己持有的任务租约（fencing token 保持不变，只有接管才递增）
        for (Map.Entry<String, Long> entry : ownedTasks.entrySet()) {
            Long token = ownedTokens.get(entry.getKey());
            writeLease(taskFile(entry.getKey()), nodeId, now + ttlMillis, token == null ? 0L : token);
        }
    }

    @Override
    public boolean isLeader() {
        ensureStarted();
        if (nodeId.equals(leaderNodeId) && System.currentTimeMillis() < leaderLeaseUntil) {
            return true;
        }
        tryElectLeader();
        return nodeId.equals(leaderNodeId);
    }

    @Override
    public boolean acquireTaskLease(String metaId) {
        ensureStarted();
        if (metaId == null || metaId.isEmpty()) {
            return false;
        }
        File f = taskFile(metaId);
        long now = System.currentTimeMillis();
        Lease current = readLease(f);
        if (current != null && !nodeId.equals(current.nodeId) && current.expiresAt > now) {
            return false;   // 其它存活节点持有
        }
        // Wave24：新租约的 token 必须严格大于历史 token，旧节点恢复后立刻失效
        long nextToken = Math.max(current == null ? 0L : current.token, monotonicToken.get()) + 1L;
        monotonicToken.set(nextToken);
        if (!writeLease(f, nodeId, now + ttlMillis, nextToken)) {
            return false;
        }
        ownedTasks.put(metaId, now + ttlMillis);
        ownedTokens.put(metaId, nextToken);
        return true;
    }

    @Override
    public long currentFencingToken(String metaId) {
        if (metaId == null) {
            return 0L;
        }
        Long own = ownedTokens.get(metaId);
        if (own != null) {
            return own;
        }
        Lease lease = readLease(taskFile(metaId));
        return lease == null ? 0L : lease.token;
    }

    @Override
    public boolean isFencingTokenValid(String metaId, long token) {
        if (metaId == null) {
            return true;
        }
        Lease lease = readLease(taskFile(metaId));
        // 租约丢失 / token 已被新节点推进 / 租约过期：一律拒绝陈旧节点写入
        return lease != null && lease.token == token && nodeId.equals(lease.nodeId)
                && lease.expiresAt > System.currentTimeMillis();
    }

    @Override
    public void releaseTaskLease(String metaId) {
        if (metaId == null) {
            return;
        }
        ownedTasks.remove(metaId);
        ownedTokens.remove(metaId);
        Lease current = readLease(taskFile(metaId));
        if (current == null || nodeId.equals(current.nodeId)) {
            taskFile(metaId).delete();
        }
    }

    @Override
    public List<String> takeoverExpiredTasks() {
        if (!isLeader()) {
            return Collections.emptyList();
        }
        List<String> taken = new ArrayList<String>();
        File[] files = leaseDir().listFiles();
        if (files == null) {
            return taken;
        }
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (!f.getName().startsWith("task-") || !f.getName().endsWith(".lease")) {
                continue;
            }
            Lease lease = readLease(f);
            if (lease == null || lease.expiresAt > now || nodeId.equals(lease.nodeId)) {
                continue;
            }
            String metaId = f.getName().substring(5, f.getName().length() - 6);
            if (acquireTaskLease(metaId)) {
                taken.add(metaId);
                logger.info("takeover expired task lease: {} from {}", metaId, lease.nodeId);
            }
        }
        return taken;
    }

    @Override
    public Map<String, Object> clusterState() {
        ensureStarted();
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("nodeId", nodeId);
        state.put("role", isLeader() ? "LEADER" : "FOLLOWER");
        state.put("leaderNodeId", leaderNodeId);
        state.put("leaseUntil", leaderLeaseUntil);
        state.put("heartbeats", heartbeatCount);
        state.put("ownedTasks", new ArrayList<String>(ownedTasks.keySet()));
        state.put("leaseDir", leaseDir().getAbsolutePath());
        state.put("mode", "standalone-file-lease");
        state.put("sharedStorageRequired", true);
        state.put("fencingTokens", new LinkedHashMap<String, Long>(ownedTokens));
        state.put("fencingMode", "monotonic-lease-token");
        return state;
    }

    /**
     * 延迟启动：@Bean 创建时系统配置桥接（租约目录）可能尚未安装，
     * 因此不在 PostConstruct 抢 leader，改为首次 isLeader / 租约操作时启动，
     * 保证使用的是系统配置页里配置的共享目录。
     */
    @javax.annotation.PostConstruct
    public void init() {
        // no-op，见 ensureStarted()
    }

    @javax.annotation.PreDestroy
    public void destroy() {
        stop();
    }

    private void tryElectLeader() {
        File f = new File(leaseDir(), "leader.lease");
        long now = System.currentTimeMillis();
        Lease current = readLease(f);
        if (current != null && !nodeId.equals(current.nodeId) && current.expiresAt > now) {
            leaderNodeId = current.nodeId;
            leaderLeaseUntil = current.expiresAt;
            return;
        }
        if (writeLease(f, nodeId, now + ttlMillis)) {
            leaderNodeId = nodeId;
            leaderLeaseUntil = now + ttlMillis;
        }
    }

    private boolean writeLease(File f, String owner, long expiresAt) {
        return writeLease(f, owner, expiresAt, 0L);
    }

    /** Wave24：租约文件内容 owner|expiresAt|fencingToken（三段格式） */
    private boolean writeLease(File f, String owner, long expiresAt, long token) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            Files.write(tmp.toPath(), (owner + "|" + expiresAt + "|" + token).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            logger.warn("write lease failed {}: {}", f.getName(), e.getMessage());
            return false;
        }
    }

    /** 兼容 Wave23 两段格式（token=0）与 Wave24 三段格式 */
    private Lease readLease(File f) {
        if (!f.isFile()) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            int last = text.lastIndexOf('|');
            if (last < 0) {
                return null;
            }
            String tail = text.substring(last + 1);
            String head = text.substring(0, last);
            int second = head.lastIndexOf('|');
            if (second >= 0 && isNumeric(tail)) {
                long token = Long.parseLong(tail);
                long expiresAt = Long.parseLong(head.substring(second + 1));
                return new Lease(head.substring(0, second), expiresAt, token);
            }
            return new Lease(head, Long.parseLong(tail), 0L);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isNumeric(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private File taskFile(String metaId) {
        String safe = metaId.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.length() > 120) {
            safe = safe.substring(0, 120);
        }
        return new File(leaseDir(), "task-" + safe + ".lease");
    }

    private String resolveNodeId() {
        String host = "localhost";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ignored) {
            // ignore
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        return host + "-" + pid + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static final class Lease {
        final String nodeId;
        final long expiresAt;
        /** Wave24：单调递增的 fencing token */
        final long token;

        Lease(String nodeId, long expiresAt) {
            this(nodeId, expiresAt, 0L);
        }

        Lease(String nodeId, long expiresAt, long token) {
            this.nodeId = nodeId;
            this.expiresAt = expiresAt;
            this.token = token;
        }
    }
}
