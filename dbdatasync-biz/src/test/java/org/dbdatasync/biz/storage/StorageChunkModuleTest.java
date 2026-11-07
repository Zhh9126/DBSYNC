package org.dbdatasync.biz.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * 存储分块外置模块测试：覆盖 store→resolve 往返、多字节/代理对边界、inline 阈值、
 * 协议头校验、缺块、删除与非法参数等场景。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class StorageChunkModuleTest {

    /**
     * 场景 1：纯 ASCII 大文本（>65535 字节）store→resolve 往返一致，且 stored 是 REF 前缀。
     */
    @Test
    public void testStoreAndResolveAsciiOversizedContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz"); // 70 字节
        }
        String content = sb.toString();
        assertTrue(OversizedContentOffloader.utf8Length(content) > 65535);

        InMemoryChunkStore store = new InMemoryChunkStore();
        String stored = OversizedContentOffloader.store(content, store, OversizedContentOffloader.INLINE_THRESHOLD_DEFAULT);
        assertTrue("超过阈值应返回 REF 引用串", stored.startsWith(OversizedContentOffloader.REF_PREFIX));

        String resolved = OversizedContentOffloader.resolve(stored, store);
        assertEquals(content, resolved);
    }

    /**
     * 场景 2：含中文+emoji 的分块，切块边界不落多字节序列/代理对中间；
     * 逐块 UTF-8 字节数 <= 上限且 store→resolve 往返一致。
     */
    @Test
    public void testChunkBoundarySafetyWithMultibyteAndSurrogatePairs() throws Exception {
        StringBuilder sb = new StringBuilder();
        String unit = "中文字符😀😀😀ABCde"; // 中文 3 字节/字 + emoji 代理对 4 字节
        for (int i = 0; i < 2000; i++) {
            sb.append(unit);
        }
        String content = sb.toString();
        int maxChunkBytes = 97; // 非单位对齐，迫使边界落在多字节/代理对附近

        InMemoryChunkStore store = new InMemoryChunkStore();
        String stored = OversizedContentOffloader.store(content, store, maxChunkBytes);
        assertTrue(stored.startsWith(OversizedContentOffloader.REF_PREFIX));
        String refId = stored.substring(OversizedContentOffloader.REF_PREFIX.length());

        List<String> chunks = ContentChunker.split(content, maxChunkBytes);
        assertTrue("应切出多个块", chunks.size() > 1);
        // 协议头本身占字节，因此只校验协议头之后的负载：逐块 UTF-8 字节数 <= 上限，
        // 且负载作为合法 UTF-8 解码时代理对未被拆散（高位代理必紧跟低位代理）
        for (int i = 0; i < chunks.size(); i++) {
            String raw = store.load(refId, i);
            assertNotNull("块 " + i + " 应存在于 ChunkStore", raw);
            int nl = raw.indexOf('\n');
            String payload = raw.substring(nl + 1);
            byte[] payloadBytes = payload.getBytes("UTF-8");
            assertTrue("块 " + i + " 负载字节数超上限: " + payloadBytes.length,
                    payloadBytes.length <= maxChunkBytes);
            int hi = payload.indexOf('\uD83D');
            if (hi >= 0) {
                assertTrue("高位代理后必须紧跟低位代理", hi + 1 < payload.length()
                        && Character.isLowSurrogate(payload.charAt(hi + 1)));
            }
        }

        String resolved = OversizedContentOffloader.resolve(stored, store);
        assertEquals("多字节文本往返必须一致", content, resolved);
    }

    /**
     * 场景 3：inline 阈值内原样返回，不产生 REF 引用串，也不写入 ChunkStore。
     */
    @Test
    public void testInlineContentReturnedAsIs() {
        String content = "短内容：中文😀inline";
        InMemoryChunkStore store = new InMemoryChunkStore();
        String stored = OversizedContentOffloader.store(content, store, OversizedContentOffloader.INLINE_THRESHOLD_DEFAULT);
        assertEquals("阈值内应原样返回", content, stored);
        assertFalse("不应写入 ChunkStore", store.exists(""));
        assertFalse("不应有 REF 前缀", stored.startsWith(OversizedContentOffloader.REF_PREFIX));
        // resolve 对 inline 内容原样返回
        assertEquals(content, OversizedContentOffloader.resolve(stored, store));
    }

    /**
     * 场景 4：手工篡改某块负载后 assemble 抛 IllegalStateException。
     */
    @Test
    public void testAssembleFailsOnTamperedChunk() {
        StringBuilder sb = new StringBuilder();
        String unit = "中文字符😀😀😀";
        for (int i = 0; i < 800; i++) {
            sb.append(unit);
        }
        List<String> chunks = ContentChunker.split(sb.toString(), 1024);
        assertTrue(chunks.size() > 1);

        String tampered = chunks.get(1);
        int nl = tampered.indexOf('\n');
        chunks.set(1, tampered.substring(0, nl + 1) + "TAMPERED" + tampered.substring(nl + 1));

        try {
            ChunkAssembler.assemble(chunks);
            fail("篡改负载后重组必须失败");
        } catch (IllegalStateException expected) {
            assertTrue("异常消息应指明摘要不一致: " + expected.getMessage(),
                    expected.getMessage().contains("SHA-256"));
        }
    }

    /**
     * 场景 5：缺块 resolve 抛 IllegalStateException。
     */
    @Test
    public void testResolveFailsWhenChunkMissing() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        }
        InMemoryChunkStore store = new InMemoryChunkStore();
        String stored = OversizedContentOffloader.store(sb.toString(), store, OversizedContentOffloader.INLINE_THRESHOLD_DEFAULT);
        String refId = stored.substring(OversizedContentOffloader.REF_PREFIX.length());

        // 构造缺块存储：除第 1 块外全部复制到新 store
        String countPart = refId.substring(refId.lastIndexOf('-') + 1);
        int total = Integer.parseInt(countPart);
        assertTrue("测试前提：应切出至少 2 块", total >= 2);
        InMemoryChunkStore brokenStore = new InMemoryChunkStore();
        for (int i = 0; i < total; i++) {
            if (i == 1) {
                continue; // 模拟第 1 块缺失
            }
            brokenStore.save(refId, i, store.load(refId, i));
        }
        try {
            OversizedContentOffloader.resolve(stored, brokenStore);
            fail("缺块 resolve 必须失败");
        } catch (IllegalStateException expected) {
            assertTrue("异常消息应指明缺块: " + expected.getMessage(),
                    expected.getMessage().contains("缺失"));
        }
    }

    /**
     * 场景 6：delete 后 exists=false，且 resolve 抛 IllegalStateException。
     */
    @Test
    public void testDeleteRemovesAllChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            sb.append("0123456789abcdefABCDEF"); // 22 字节 × 4000 = 88000 字节 > 65535
        }
        InMemoryChunkStore store = new InMemoryChunkStore();
        String stored = OversizedContentOffloader.store(sb.toString(), store, OversizedContentOffloader.INLINE_THRESHOLD_DEFAULT);
        String refId = stored.substring(OversizedContentOffloader.REF_PREFIX.length());
        assertTrue(store.exists(refId));

        OversizedContentOffloader.delete(stored, store);
        assertFalse("delete 后 exists 必须为 false", store.exists(refId));
        try {
            OversizedContentOffloader.resolve(stored, store);
            fail("delete 后 resolve 必须失败");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("不存在任何分块"));
        }
    }

    /**
     * 场景 7：maxChunkBytes 非法（<= 0）抛 IllegalArgumentException。
     */
    @Test
    public void testSplitRejectsInvalidMaxChunkBytes() {
        try {
            ContentChunker.split("abc", 0);
            fail("maxChunkBytes=0 必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            ContentChunker.split("abc", -1);
            fail("maxChunkBytes<0 必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * 补充：null 内容视为空串；utf8Length 工具正确性。
     */
    @Test
    public void testNullContentAndUtf8Length() {
        List<String> chunks = ContentChunker.split(null, 100);
        assertEquals(1, chunks.size());
        assertEquals("", ChunkAssembler.assemble(chunks));
        assertEquals(0, OversizedContentOffloader.utf8Length(null));
        assertEquals(0, OversizedContentOffloader.utf8Length(""));
        assertEquals(3, OversizedContentOffloader.utf8Length("中"));
        assertEquals(4, OversizedContentOffloader.utf8Length("😀"));
    }
}
