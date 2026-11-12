package org.dbdatasync.sdk.checkpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * 幂等键生成：位点 + 表 + 事件类型 + 行内容摘要。
 *
 * <p>位点相同但行不同（同一事务多行）仍会产生不同键；位点缺失时退化为行内容哈希，
 * 保证重放/重启后同一条变更只会写入一次。</p>
 */
public final class IdempotencyKey {

    private IdempotencyKey() {
    }

    public static String of(String metaId, String fileName, Object position, long seq,
                            String table, String event, Object row) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(safe(metaId)).append('|')
                .append(safe(fileName)).append('|')
                .append(safe(position == null ? null : String.valueOf(position))).append('|')
                .append(seq).append('|')
                .append(safe(table)).append('|')
                .append(safe(event)).append('|')
                .append(rowDigest(row));
        return sha256Hex(sb.toString());
    }

    /** 稳定行摘要：Map 按键排序，List 按序，避免 HashMap 遍历顺序影响结果 */
    public static String rowDigest(Object row) {
        StringBuilder sb = new StringBuilder(128);
        appendStable(sb, row);
        return sha256Hex(sb.toString());
    }

    private static void appendStable(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("<null>;");
        } else if (value instanceof Map) {
            sb.append("{");
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<String, Object>();
            for (Object entry : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) entry;
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                sb.append(e.getKey()).append('=');
                appendStable(sb, e.getValue());
                sb.append(';');
            }
            sb.append("}");
        } else if (value instanceof List) {
            sb.append("[");
            for (Object item : (List<?>) value) {
                appendStable(sb, item);
                sb.append(',');
            }
            sb.append("]");
        } else if (value instanceof byte[]) {
            sb.append(java.util.Arrays.toString((byte[]) value));
        } else {
            sb.append(value.toString());
        }
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 规范要求 SHA-256 必须存在，退化到 JVM 哈希仅用于极端环境
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "_").replace("\n", " ").replace("\r", " ");
    }
}
