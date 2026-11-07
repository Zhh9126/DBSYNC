package org.dbdatasync.biz.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link FileChunkStore} 单元测试：基于 JUnit 临时目录，覆盖保存/加载/删除/存在性
 * 与中文多字节内容、路径净化、重启恢复等场景。
 */
public class FileChunkStoreTest {

    /** JUnit 临时目录规则。 */
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void saveAndLoadRoundTrip() throws IOException {
        File root = tmp.newFolder("store1");
        FileChunkStore store = new FileChunkStore(root);
        store.save("ref-a", 0, "hello");
        store.save("ref-a", 1, "world");
        assertEquals("hello", store.load("ref-a", 0));
        assertEquals("world", store.load("ref-a", 1));
        assertTrue(store.exists("ref-a"));
        assertFalse(store.exists("ref-b"));
    }

    @Test
    public void loadMissingReturnsNull() throws IOException {
        File root = tmp.newFolder("store2");
        FileChunkStore store = new FileChunkStore(root);
        assertNull(store.load("no-such-ref", 0));
        assertNull(store.load("no-such-ref", 9));
    }

    @Test
    public void chineseMultibyteRoundTrip() throws IOException {
        File root = tmp.newFolder("store3");
        FileChunkStore store = new FileChunkStore(root);
        String payload = "中文内容：数据库同步，emoji 不可用但多字节汉字如 龘 靐 必须保真";
        store.save("中文引用", 0, payload);
        assertEquals(payload, store.load("中文引用", 0));
        // 中文 refId 被净化为下划线目录，仍可一致往返
        assertEquals(payload, store.load("中文引用", 0));
    }

    @Test
    public void pathTraversalSanitized() throws IOException {
        File root = tmp.newFolder("store4");
        FileChunkStore store = new FileChunkStore(root);
        // 恶意 refId 含路径穿越字符，必须被净化，不逃出根目录
        store.save("../../etc/passwd", 0, "payload");
        assertEquals("payload", store.load("../../etc/passwd", 0));
        // 净化后目录应在根目录内
        assertTrue(store.exists("../../etc/passwd"));
        // 根目录之外不存在名为 etc 的目录（穿越被阻断）
        assertFalse(new File(root.getParentFile(), "etc").exists());
    }

    @Test
    public void parentDotDotRefIdCannotEscapeRoot() throws IOException {
        File root = tmp.newFolder("store4b");
        FileChunkStore store = new FileChunkStore(root);
        // refId 为 ".." 时必须被转义，数据落在根目录内的 "__" 子目录而不是根目录的父目录
        store.save("..", 0, "escaped?");
        assertEquals("escaped?", store.load("..", 0));
        File escapedDir = new File(root.getParentFile(), "__");
        assertFalse("数据不得逃逸到根目录之外: " + escapedDir, escapedDir.exists());
        assertTrue(new File(root, "__").isDirectory());
    }

    @Test
    public void deleteRemovesAllChunksAndDir() throws IOException {
        File root = tmp.newFolder("store5");
        FileChunkStore store = new FileChunkStore(root);
        store.save("ref-del", 0, "a");
        store.save("ref-del", 1, "b");
        store.save("ref-del", 2, "c");
        assertTrue(store.exists("ref-del"));
        store.delete("ref-del");
        assertFalse(store.exists("ref-del"));
        assertNull(store.load("ref-del", 0));
        // 删除后再保存可复用
        store.save("ref-del", 0, "again");
        assertEquals("again", store.load("ref-del", 0));
    }

    @Test
    public void persistenceAcrossInstances() throws IOException {
        File root = tmp.newFolder("store6");
        FileChunkStore first = new FileChunkStore(root);
        first.save("persist-ref", 0, "chunk-zero");
        first.save("persist-ref", 1, "chunk-one");
        // 新实例（模拟进程重启）仍可读取
        FileChunkStore second = new FileChunkStore(root);
        assertEquals("chunk-zero", second.load("persist-ref", 0));
        assertEquals("chunk-one", second.load("persist-ref", 1));
        assertTrue(second.exists("persist-ref"));
    }

    @Test
    public void overwriteSameIndex() throws IOException {
        File root = tmp.newFolder("store7");
        FileChunkStore store = new FileChunkStore(root);
        store.save("ref-ow", 0, "old");
        store.save("ref-ow", 0, "new");
        assertEquals("new", store.load("ref-ow", 0));
    }

    @Test
    public void autoCreateRootDirWhenMissing() throws IOException {
        File root = new File(tmp.getRoot(), "not-created-yet/deep/dir");
        FileChunkStore store = new FileChunkStore(root);
        store.save("ref-auto", 0, "data");
        assertTrue(Files.exists(root.toPath()));
        assertEquals("data", store.load("ref-auto", 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyRefIdRejected() throws IOException {
        File root = tmp.newFolder("store8");
        FileChunkStore store = new FileChunkStore(root);
        store.save("", 0, "data");
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeIndexRejected() throws IOException {
        File root = tmp.newFolder("store9");
        FileChunkStore store = new FileChunkStore(root);
        store.save("ref-neg", -1, "data");
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullPayloadRejected() throws IOException {
        File root = tmp.newFolder("store10");
        FileChunkStore store = new FileChunkStore(root);
        store.save("ref-null", 0, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyRootDirRejected() {
        new FileChunkStore("  ");
    }

    @Test
    public void sanitizeMapping() {
        assertEquals("abc-DEF_123", FileChunkStore.sanitize("abc-DEF_123"));
        assertEquals("a_b_c", FileChunkStore.sanitize("a/b\\c"));
        // 点号保留，但 "." 与 ".." 特殊路径分量整体替换为下划线防逃逸
        assertEquals("..__", FileChunkStore.sanitize("../\\"));
        assertEquals("_", FileChunkStore.sanitize("."));
        assertEquals("__", FileChunkStore.sanitize(".."));
        // 含穿越路径的整体 refId 被压缩为单层目录名
        assertEquals(".._.._etc_passwd", FileChunkStore.sanitize("../../etc/passwd"));
        // 尾部点号替换为下划线（Windows 目录名限制）
        assertEquals("ref_", FileChunkStore.sanitize("ref."));
    }

    @Test
    public void oversizedContentIntegration() {
        // 与 OversizedContentOffloader 组合使用：小阈值下分块外置再还原
        File root = new File(tmp.getRoot(), "store11");
        FileChunkStore store = new FileChunkStore(root);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            big.append("数据块内容-").append(i).append("；");
        }
        String original = big.toString();
        String stored = OversizedContentOffloader.store(original, store, 512);
        assertTrue(stored.startsWith("DBDS-REF:"));
        String resolved = OversizedContentOffloader.resolve(stored, store);
        assertEquals(original, resolved);
        OversizedContentOffloader.delete(stored, store);
        assertFalse(store.exists(stored.substring(OversizedContentOffloader.REF_PREFIX.length())));
    }
}
