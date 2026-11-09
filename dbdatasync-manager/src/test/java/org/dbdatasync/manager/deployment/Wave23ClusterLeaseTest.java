package org.dbdatasync.manager.deployment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wave23 P0-C 离线故障注入测试：leader 选举、任务独占租约、释放与过期接管。
 * 使用共享临时目录模拟多节点共用的 NFS 目录，不启动真实服务、不连数据库。
 */
public class Wave23ClusterLeaseTest {

    private File dir;
    private final List<StandaloneProvider> nodes = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("dbdatasync-wave23-lease").toFile();
    }

    @After
    public void tearDown() {
        for (StandaloneProvider p : nodes) {
            p.stop();
        }
        deleteRecursively(dir);
    }

    @Test
    public void onlyOneLeaderAndOneTaskOwner() {
        long ttl = 5000L;
        long heartbeat = 60_000L;
        StandaloneProvider a = node(ttl, heartbeat);
        StandaloneProvider b = node(ttl, heartbeat);
        a.start();
        b.start();

        assertTrue("先启动节点应持有 leader 租约", a.isLeader());
        assertFalse("第二个节点应为 FOLLOWER", b.isLeader());

        assertTrue(a.acquireTaskLease("meta-1"));
        assertFalse("存活节点持有期间，其它节点不得抢占同一任务", b.acquireTaskLease("meta-1"));

        a.releaseTaskLease("meta-1");
        assertTrue("释放后其它节点可取得租约", b.acquireTaskLease("meta-1"));

        Map<String, Object> state = a.clusterState();
        assertEquals(Boolean.TRUE, state.get("sharedStorageRequired"));
        assertEquals("standalone-file-lease", state.get("mode"));
        assertTrue(((List<?>) state.get("ownedTasks")).isEmpty());
    }

    @Test
    public void takeoverExpiredTaskLeases() throws Exception {
        StandaloneProvider leader = node(200L, 60_000L);
        leader.start();
        assertTrue(leader.isLeader());

        // 模拟一个崩溃节点留下的过期任务租约
        File expired = new File(dir, "task-meta-9.lease");
        Files.write(expired.toPath(), ("dead-node|" + (System.currentTimeMillis() - 5000L))
                .getBytes(StandardCharsets.UTF_8));

        List<String> taken = leader.takeoverExpiredTasks();
        assertTrue("过期租约应被接管: " + taken, taken.contains("meta-9"));
        assertFalse("接管后归属本节点，不应重复接管", leader.takeoverExpiredTasks().contains("meta-9"));
    }

    @Test
    public void dynamicLeaseDirProviderIsHonored() throws Exception {
        File alternate = Files.createTempDirectory("dbdatasync-wave23-lease-alt").toFile();
        StandaloneProvider p = node(5000L, 60_000L);
        p.setLeaseDirProvider(() -> alternate);
        p.start();
        assertTrue(p.isLeader());
        assertTrue(p.acquireTaskLease("meta-x"));
        assertTrue("租约文件应落在系统配置指定的目录", new File(alternate, "task-meta-x.lease").isFile());
        deleteRecursively(alternate);
    }

    private StandaloneProvider node(long ttl, long heartbeat) {
        StandaloneProvider p = new StandaloneProvider(dir, ttl, heartbeat);
        nodes.add(p);
        return p;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }
}
