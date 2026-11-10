package org.dbdatasync.biz.storage;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 超大内容外置器：为超过 inline 阈值（默认 65535 字节，即既有 H2 DATA 字段上限）的内容
 * 提供「分块外置 + 引用串回填」的非破坏式扩容路径。
 * <p>
 * 内联阈值内的内容原样返回；超限内容按 {@link ContentChunker} 切块后写入
 * {@link ChunkStore}，原字段只存引用串（形如 {@code DBDS-REF:<refId>}）。
 * 读取时按引用串从 ChunkStore 加载全部块并经 {@link ChunkAssembler} 校验重组。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public final class OversizedContentOffloader {

    /** 引用串前缀。 */
    public static final String REF_PREFIX = "DBDS-REF:";

    /** 默认内联阈值：65535 字节。 */
    public static final int INLINE_THRESHOLD_DEFAULT = 65535;

    /** 单块目标大小上限：避免极端小的 maxInlineBytes 导致块数爆炸。 */
    private static final int CHUNK_SIZE_CAP = 60000;

    /** UTF-8 字符集。 */
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** 工具类，禁止实例化。 */
    private OversizedContentOffloader() {
    }

    /**
     * 存储内容：阈值内原样返回；超限则分块外置并返回引用串。
     *
     * @param content        原文；null 视为空串
     * @param store          分块存储
     * @param maxInlineBytes 内联阈值（字节），&lt;= 0 时按默认阈值 65535 处理
     * @return inline 原文，或形如 {@code DBDS-REF:<refId>} 的引用串
     * @throws IllegalArgumentException 当 maxInlineBytes 与内容均需分块但块大小非法时
     *                                  （块大小 = min(60000, maxInlineBytes)，若仍 &lt;= 0 则抛出）
     */
    public static String store(String content, ChunkStore store, int maxInlineBytes) {
        int threshold = maxInlineBytes > 0 ? maxInlineBytes : INLINE_THRESHOLD_DEFAULT;
        String src = content == null ? "" : content;
        if (utf8Length(src) <= threshold) {
            return src;
        }

        int chunkBytes = Math.min(CHUNK_SIZE_CAP, threshold);
        if (chunkBytes <= 0) {
            throw new IllegalArgumentException("计算出的块大小非法: " + chunkBytes);
        }

        List<String> chunks = ContentChunker.split(src, chunkBytes);
        // Wave30：nanoTime 在部分 JVM/OS 上精度只有微秒级，多线程同批数内容会拿到相同 refId，
        // 后写块覆盖先写块导致重组 SHA 校验失败甚至串读到别的明细。改用 UUID 保证唯一。
        String refId = "c" + UUID.randomUUID().toString().replace("-", "") + "-" + chunks.size();
        for (int i = 0; i < chunks.size(); i++) {
            store.save(refId, i, chunks.get(i));
        }
        return REF_PREFIX + refId;
    }

    /**
     * 解析存储值：引用串则加载全部分块并校验重组；否则原样返回。
     *
     * @param stored 存储值（inline 原文或引用串）
     * @param store  分块存储
     * @return 原文
     * @throws IllegalStateException 当引用串指向的某块缺失，或重组校验失败
     */
    public static String resolve(String stored, ChunkStore store) {
        if (stored == null || !stored.startsWith(REF_PREFIX)) {
            return stored;
        }
        String refId = stored.substring(REF_PREFIX.length());
        List<String> chunks = loadChunks(refId, store);
        return ChunkAssembler.assemble(chunks);
    }

    /**
     * 删除引用串指向的全部分块。非引用串时为空操作。
     *
     * @param stored 存储值（inline 原文或引用串）
     * @param store  分块存储
     */
    public static void delete(String stored, ChunkStore store) {
        if (stored == null || !stored.startsWith(REF_PREFIX)) {
            return;
        }
        String refId = stored.substring(REF_PREFIX.length());
        store.delete(refId);
    }

    /**
     * 按引用串从存储加载全部块（顺序读取直到缺块）。
     *
     * @param refId 引用标识
     * @param store 分块存储
     * @return 块列表
     * @throws IllegalStateException 当任一块缺失
     */
    private static List<String> loadChunks(String refId, ChunkStore store) {
        String first = store.load(refId, 0);
        if (first == null) {
            throw new IllegalStateException("外置内容解析失败：引用 " + refId + " 下不存在任何分块");
        }
        int totalChunks = readTotalChunks(refId, first);
        List<String> chunks = new ArrayList<String>(totalChunks);
        chunks.add(first);
        for (int i = 1; i < totalChunks; i++) {
            String chunk = store.load(refId, i);
            if (chunk == null) {
                throw new IllegalStateException("外置内容解析失败：引用 " + refId + " 的第 " + i + " 块缺失");
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * 从首块协议头解析 totalChunks。
     *
     * @param refId 引用标识（仅用于报错信息）
     * @param first 首块内容
     * @return 声明的总块数
     * @throws IllegalStateException 当首块缺少合法协议头
     */
    private static int readTotalChunks(String refId, String first) {
        int nl = first.indexOf('\n');
        String headerLine = nl < 0 ? first : first.substring(0, nl);
        if (!headerLine.startsWith(ContentChunker.HEADER_PREFIX)) {
            throw new IllegalStateException("外置内容解析失败：引用 " + refId + " 的首块协议头非法: " + headerLine);
        }
        String body = headerLine.substring(ContentChunker.HEADER_PREFIX.length());
        int sep1 = body.indexOf('|');
        int sep2 = body.indexOf('|', sep1 + 1);
        if (sep1 < 0 || sep2 < 0) {
            throw new IllegalStateException("外置内容解析失败：引用 " + refId + " 的首块协议头字段非法: " + headerLine);
        }
        try {
            return Integer.parseInt(body.substring(0, sep1));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("外置内容解析失败：引用 " + refId + " 的首块协议头 totalChunks 非法: " + headerLine);
        }
    }

    /**
     * 计算字符串的 UTF-8 字节长度。
     *
     * @param content 待计算字符串；null 视为空串
     * @return UTF-8 编码后的字节数
     */
    public static int utf8Length(String content) {
        if (content == null) {
            return 0;
        }
        return content.getBytes(UTF_8).length;
    }
}
