package org.dbdatasync.biz.storage;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 内容切块器：将超大字符串按 UTF-8 字节上限切分为多个块，并在每块头部写入协议标记。
 * <p>
 * 切块保证字节边界安全：不会在 UTF-8 多字节序列中间切开，也不会把 char 代理对
 * （surrogate pair，如 emoji）切到两个块中——遍历 char 时遇到高位代理，必与其低位代理同块。
 * </p>
 * <p>
 * 头部协议（每块第一行）：
 * <pre>DBDS-CHUNK/v1|&lt;totalChunks&gt;|&lt;chunkIndex&gt;|&lt;sha256Hex(整段原文)&gt;</pre>
 * 换行之后为本块真实负载。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public final class ContentChunker {

    /** 协议头标记前缀。 */
    public static final String HEADER_PREFIX = "DBDS-CHUNK/v1|";

    /** 协议头字段分隔符。 */
    private static final char SEP = '|';

    /** UTF-8 字符集。 */
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** 工具类禁止实例化以外的方式创建（仍允许同包按需实例化使用实例方法）。 */
    private ContentChunker() {
    }

    /**
     * 将原文切块并附加协议头。
     *
     * @param content       原文；null 视为空串
     * @param maxChunkBytes 每块允许的最大 UTF-8 字节数，必须 &gt; 0
     * @return 块列表（已含协议头），至少包含一个块
     * @throws IllegalArgumentException 当 maxChunkBytes &lt;= 0
     */
    public static List<String> split(String content, int maxChunkBytes) {
        if (maxChunkBytes <= 0) {
            throw new IllegalArgumentException("maxChunkBytes 必须大于 0，实际值: " + maxChunkBytes);
        }
        String src = content == null ? "" : content;

        List<StringBuilder> payloads = splitPayloads(src, maxChunkBytes);
        String sha256 = sha256Hex(src);
        int totalChunks = payloads.size();

        List<String> result = new ArrayList<String>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            StringBuilder header = new StringBuilder();
            header.append(HEADER_PREFIX)
                    .append(totalChunks).append(SEP)
                    .append(i).append(SEP)
                    .append(sha256)
                    .append('\n');
            header.append(payloads.get(i));
            result.add(header.toString());
        }
        return result;
    }

    /**
     * 按 UTF-8 字节上限切分原文负载，保证不在多字节序列或代理对中间切开。
     *
     * @param src           原文
     * @param maxChunkBytes 每块最大 UTF-8 字节数
     * @return 负载块列表（不含协议头），至少一个块
     */
    private static List<StringBuilder> splitPayloads(String src, int maxChunkBytes) {
        List<StringBuilder> payloads = new ArrayList<StringBuilder>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        final int len = src.length();

        for (int i = 0; i < len; ) {
            char c = src.charAt(i);
            int charCount = 1;
            if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(src.charAt(i + 1))) {
                charCount = 2;
            }
            int byteCount = src.substring(i, i + charCount).getBytes(UTF_8).length;

            if (currentBytes > 0 && currentBytes + byteCount > maxChunkBytes) {
                payloads.add(current);
                current = new StringBuilder();
                currentBytes = 0;
            }
            current.append(src, i, i + charCount);
            currentBytes += byteCount;
            i += charCount;
        }
        payloads.add(current);
        return payloads;
    }

    /**
     * 计算字符串 UTF-8 字节的 SHA-256 摘要，输出小写 hex。
     *
     * @param content 待摘要字符串
     * @return 64 位小写 hex 摘要
     */
    public static String sha256Hex(String content) {
        byte[] bytes = content.getBytes(UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return toLowerHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256 摘要算法", e);
        }
    }

    /**
     * 将字节数组转为小写 hex 字符串。
     *
     * @param bytes 字节数组
     * @return hex 字符串
     */
    private static String toLowerHex(byte[] bytes) {
        char[] hexChars = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            sb.append(hexChars[v >>> 4]);
            sb.append(hexChars[v & 0x0F]);
        }
        return sb.toString();
    }
}
