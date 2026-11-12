package org.dbdatasync.biz.storage;

import java.util.List;

/**
 * 分块重组器：解析 {@link ContentChunker} 产出的带协议头的块列表，校验后重组出原文。
 * <p>
 * 校验规则：
 * <ul>
 *   <li>每块首行必须为合法协议头 "DBDS-CHUNK/v1|totalChunks|chunkIndex|sha256"</li>
 *   <li>所有块的 totalChunks 必须一致</li>
 *   <li>chunkIndex 必须从 0 起连续递增</li>
 *   <li>实际块数必须等于 totalChunks</li>
 *   <li>重组结果重算 SHA-256 必须与标记中的摘要一致</li>
 * </ul>
 * 任一校验不满足均抛出 {@link IllegalStateException}，消息指明原因。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public final class ChunkAssembler {

    /** 协议头字段分隔符。 */
    private static final char SEP = '|';

    /** 工具类，禁止实例化。 */
    private ChunkAssembler() {
    }

    /**
     * 重组原文。
     *
     * @param chunkLines 带协议头的块列表（{@link ContentChunker#split} 的产出）
     * @return 原文
     * @throws IllegalStateException 当块列表为空、协议头非法、totalChunks 不一致、
     *                               chunkIndex 不连续、块数与 totalChunks 不符或摘要校验失败
     */
    public static String assemble(List<String> chunkLines) {
        if (chunkLines == null || chunkLines.isEmpty()) {
            throw new IllegalStateException("重组失败：块列表为空");
        }

        int totalChunks = -1;
        StringBuilder payload = new StringBuilder();

        for (int expectIndex = 0; expectIndex < chunkLines.size(); expectIndex++) {
            String chunk = chunkLines.get(expectIndex);
            Header header = parseHeader(chunk, expectIndex);

            if (totalChunks < 0) {
                totalChunks = header.totalChunks;
            } else if (totalChunks != header.totalChunks) {
                throw new IllegalStateException("重组失败：第 " + expectIndex + " 块的 totalChunks="
                        + header.totalChunks + " 与第 0 块的 totalChunks=" + totalChunks + " 不一致");
            }
            if (header.chunkIndex != expectIndex) {
                throw new IllegalStateException("重组失败：期望块序号 " + expectIndex
                        + "，实际协议头中为 " + header.chunkIndex + "，块序号必须从 0 起连续");
            }
            payload.append(header.payload);
        }

        if (chunkLines.size() != totalChunks) {
            throw new IllegalStateException("重组失败：实际块数 " + chunkLines.size()
                    + " 与协议头声明的 totalChunks=" + totalChunks + " 不相等");
        }

        String assembled = payload.toString();
        String actualSha = ContentChunker.sha256Hex(assembled);
        String declaredSha = parseHeader(chunkLines.get(0), 0).sha256;
        if (!actualSha.equals(declaredSha)) {
            throw new IllegalStateException("重组失败：重组结果 SHA-256 摘要与协议头不一致，声明值="
                    + declaredSha + "，实际值=" + actualSha);
        }
        return assembled;
    }

    /**
     * 解析单个块的协议头并提取本块负载。
     *
     * @param chunk  带协议头的块
     * @param regIdx 用于报错的块在列表中的序号
     * @return 协议头结构
     * @throws IllegalStateException 当协议头非法或字段无法解析
     */
    private static Header parseHeader(String chunk, int regIdx) {
        if (chunk == null) {
            throw new IllegalStateException("重组失败：第 " + regIdx + " 块为 null");
        }
        int nl = chunk.indexOf('\n');
        if (nl < 0) {
            throw new IllegalStateException("重组失败：第 " + regIdx + " 块缺少协议头换行符");
        }
        String headerLine = chunk.substring(0, nl);
        if (!headerLine.startsWith(ContentChunker.HEADER_PREFIX)) {
            throw new IllegalStateException("重组失败：第 " + regIdx
                    + " 块协议头前缀非法，期望前缀 " + ContentChunker.HEADER_PREFIX + "，实际: " + headerLine);
        }
        String body = headerLine.substring(ContentChunker.HEADER_PREFIX.length());
        String[] fields = splitBySep(body);
        if (fields.length != 3) {
            throw new IllegalStateException("重组失败：第 " + regIdx + " 块协议头字段数非法: " + headerLine);
        }
        int total;
        int idx;
        try {
            total = Integer.parseInt(fields[0]);
            idx = Integer.parseInt(fields[1]);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("重组失败：第 " + regIdx + " 块协议头数字字段非法: " + headerLine);
        }
        if (total <= 0) {
            throw new IllegalStateException("重组失败：第 " + regIdx + " 块协议头 totalChunks 非法: " + headerLine);
        }
        return new Header(total, idx, fields[2], chunk.substring(nl + 1));
    }

    /**
     * 以 '|' 分隔协议头字段（SHA-256 hex 不含 '|'，故可安全简单切分）。
     *
     * @param body 协议头主体
     * @return 字段数组
     */
    private static String[] splitBySep(String body) {
        java.util.List<String> parts = new java.util.ArrayList<String>(3);
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == SEP) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        return parts.toArray(new String[parts.size()]);
    }

    /** 协议头解析结果。 */
    private static final class Header {
        /** 协议头声明的总块数。 */
        final int totalChunks;
        /** 协议头声明的本块序号。 */
        final int chunkIndex;
        /** 协议头声明的整段原文 SHA-256。 */
        final String sha256;
        /** 本块真实负载。 */
        final String payload;

        Header(int totalChunks, int chunkIndex, String sha256, String payload) {
            this.totalChunks = totalChunks;
            this.chunkIndex = chunkIndex;
            this.sha256 = sha256;
            this.payload = payload;
        }
    }
}
