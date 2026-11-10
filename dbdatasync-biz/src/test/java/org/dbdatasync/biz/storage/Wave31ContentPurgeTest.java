package org.dbdatasync.biz.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Wave31：外置内容块过期清理回归。
 *
 * <p>覆盖 {@link FileChunkStore#purgeRefsOlderThan(long)} 按 mtime 回收引用目录、
 * 新块不受影响、清理后计数准确，以及 {@link OversizedContentService#purgeAgedBlocks()}
 * 对内存存储的安全降级。</p>
 */
public class Wave31ContentPurgeTest {

    /** JUnit 临时目录规则。 */
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void purgeDeletesOnlyAgedRefs() throws IOException, InterruptedException {
        File root = tmp.newFolder("purge1");
        FileChunkStore store = new FileChunkStore(root);
        store.save("old-ref", 0, "aged payload");
        store.save("fresh-ref", 0, "fresh payload");
        assertEquals(2, store.refDirCount());

        // 把 old-ref 的块文件 mtime 拨到 40 天前
        File oldDir = new File(root, FileChunkStore.sanitize("old-ref"));
        File oldChunk = new File(oldDir, "0.chunk");
        assertTrue(oldChunk.isFile());
        long aged = System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000L;
        assertTrue("set last modified failed", oldChunk.setLastModified(aged));
        assertTrue(oldDir.setLastModified(aged));

        // 截止 = 30 天前：old-ref 应被清理，fresh-ref 保留
        long cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L;
        int purged = store.purgeRefsOlderThan(cutoff);
        assertEquals(1, purged);
        assertEquals(1, store.refDirCount());
        assertFalse(store.exists("old-ref"));
        assertTrue(store.exists("fresh-ref"));
        assertEquals("fresh payload", store.load("fresh-ref", 0));
    }

    @Test
    public void purgeWithFutureCutoffClearsAll() throws IOException {
        File root = tmp.newFolder("purge2");
        FileChunkStore store = new FileChunkStore(root);
        store.save("ref-1", 0, "a");
        store.save("ref-2", 0, "b");
        store.save("ref-3", 0, "c");
        int purged = store.purgeRefsOlderThan(Long.MAX_VALUE / 2);
        assertEquals(3, purged);
        assertEquals(0, store.refDirCount());
    }

    @Test
    public void purgeOnMissingRootReturnsZero() {
        File root = new File(tmp.getRoot(), "not-exist");
        FileChunkStore store = new FileChunkStore(root);
        assertEquals(0, store.purgeRefsOlderThan(System.currentTimeMillis()));
        assertEquals(0, store.refDirCount());
    }

    @Test
    public void servicePurgeOnMemoryStoreIsNoOpSafe() {
        OversizedContentService service = new OversizedContentService();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < OversizedContentOffloader.INLINE_THRESHOLD_DEFAULT + 10; i++) {
            big.append('x');
        }
        String content = big.toString();
        String ref = service.store(content);
        assertTrue(service.isRef(ref));
        // 内存存储随进程消亡，清理是安全空操作
        assertEquals(0, service.purgeAgedBlocks());
        assertEquals(content, service.resolve(ref));
    }
}
