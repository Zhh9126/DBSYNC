package org.dbdatasync.parser.util;

import org.apache.commons.codec.digest.DigestUtils;
import org.dbdatasync.common.util.CheckSumUtil;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 字段级指纹工具（纯静态、零 Spring、可直接单测）。
 *
 * <p>用途：配合 {@code compareMode=checksum} 的分块 MD5 摘要比对。对一行数据按
 * {@code commonCols} 顺序拼接各列归一化值，并追加主键键值，整体做 MD5 摘要；对多行
 * 块逐行摘要后按序拼接再 MD5，得到「块摘要」。源/目标块摘要不一致即判定该块存在差异，
 * 但不下钻到字段级（下钻需切到 full 模式）。</p>
 *
 * <p>设计要点（对齐 {@code ValidateSyncExecutor} 既有 {@code valueEquals} 语义）：</p>
 * <ul>
 *     <li>列查找大小写不敏感（遍历 entry 用 {@code equalsIgnoreCase} 匹配），规避 PG/Oracle 大小写差异；</li>
 *     <li>{@code byte[]} 显式转十六进制小写，禁止 {@code String.valueOf(byte[])}（JVM 相关、不确定）；</li>
 *     <li>数值用 {@code BigDecimal.stripTrailingZeros().toPlainString()}，使 {@code 1} 与 {@code 1.0} 等价；</li>
 *     <li>日期/时间统一为 ISO 字符串，跨库可比较；</li>
 *     <li>MD5 用 commons-codec 1.15 既有依赖，不新增第三方依赖。</li>
 * </ul>
 */
public final class RowFingerprintUtil {

    /** 列值分隔符（非打印字符，避免与业务数据冲突） */
    private static final String FIELD_SEP = "";

    /** 主键键值分隔符 */
    private static final String PK_SEP = "|";

    /** 行分隔符 */
    private static final String ROW_SEP = "\n";

    /** 空块哨兵（固定字符串 MD5） */
    private static final String EMPTY_BLOCK_SENTINEL = "EMPTY_BLOCK";

    private RowFingerprintUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 行指纹：对 {@code commonCols} 顺序拼接 canonical 值 + FIELD_SEP + 各主键 canonical 值（PK_SEP 连接），
     * 整体 MD5 hex（小写）。列查找大小写不敏感。
     *
     * @param row        一行数据（键为列标签，值为原始/已 normalize 的字段值）
     * @param commonCols 公共列名（源顺序）
     * @param pkNames    主键列名
     * @return 32 位小写 MD5 hex
     */
    public static String rowFingerprint(Map<String, Object> row, List<String> commonCols, List<String> pkNames) {
        StringBuilder sb = new StringBuilder();
        if (commonCols != null) {
            for (String col : commonCols) {
                if (sb.length() > 0) {
                    sb.append(FIELD_SEP);
                }
                sb.append(canonical(findIgnoreCase(row, col)));
            }
        }
        sb.append(FIELD_SEP);
        if (pkNames != null) {
            StringBuilder pkSb = new StringBuilder();
            for (int i = 0; i < pkNames.size(); i++) {
                if (i > 0) {
                    pkSb.append(PK_SEP);
                }
                pkSb.append(canonical(findIgnoreCase(row, pkNames.get(i))));
            }
            sb.append(pkSb.toString());
        }
        return DigestUtils.md5Hex(sb.toString());
    }

    /**
     * 块摘要：对 {@code rows} 逐行 {@link #rowFingerprint} 后按序用 ROW_SEP 拼接再 MD5。
     * 空行表返回 MD5("EMPTY_BLOCK")（固定哨兵，保证空块幂等稳定）。
     *
     * @param rows       块内行数据（顺序需稳定，由 {@code pageRows} 按 pk 排序保证）
     * @param commonCols 公共列名
     * @param pkNames    主键列名
     * @return 32 位小写 MD5 hex
     */
    public static String blockDigest(List<Map<String, Object>> rows, List<String> commonCols, List<String> pkNames) {
        if (rows == null || rows.isEmpty()) {
            return DigestUtils.md5Hex(EMPTY_BLOCK_SENTINEL);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(ROW_SEP);
            }
            sb.append(rowFingerprint(rows.get(i), commonCols, pkNames));
        }
        return DigestUtils.md5Hex(sb.toString());
    }

    /**
     * 值归一化：
     * <ul>
     *     <li>{@code null} → ""；</li>
     *     <li>{@code byte[]} → 十六进制小写（复用 {@code CheckSumUtil.toHexString}，禁止 {@code String.valueOf(byte[])}）；</li>
     *     <li>日期/时间（java.sql.* 与 java.time.* 及 java.util.Date）→ ISO 字符串；</li>
     *     <li>数值（Number 或能 parse 的字符串）→ {@code BigDecimal.stripTrailingZeros().toPlainString()}；</li>
     *     <li>其余 → {@code String.valueOf(v)}。</li>
     * </ul>
     *
     * @param value 原始值
     * @return 归一化字符串
     */
    public static String canonical(Object value) {
        if (value == null) {
            return "";
        }
        // 二进制 -> 十六进制小写（确定、跨 JVM 一致）
        if (value instanceof byte[]) {
            return CheckSumUtil.toHexString((byte[]) value);
        }
        // 日期/时间统一为 ISO 字符串，避免跨库表示差异
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().toString();
        }
        if (value instanceof java.sql.Time) {
            return ((java.sql.Time) value).toLocalTime().toString();
        }
        if (value instanceof java.time.LocalDateTime || value instanceof java.time.LocalDate
                || value instanceof java.time.LocalTime || value instanceof java.time.ZonedDateTime
                || value instanceof java.time.OffsetDateTime || value instanceof java.time.Instant) {
            return String.valueOf(value);
        }
        if (value instanceof java.util.Date && !(value instanceof java.sql.Date)) {
            return new java.sql.Timestamp(((java.util.Date) value).getTime()).toLocalDateTime().toString();
        }
        // 数值归一：使 1 与 1.0 等价（对齐 valueEquals 语义）
        if (value instanceof Number) {
            return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
        }
        if (value instanceof String) {
            String s = (String) value;
            try {
                return new BigDecimal(s).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return s;
            }
        }
        return String.valueOf(value);
    }

    /**
     * 大小写不敏感查找列值（规避 PG/Oracle 大小写差异）。
     *
     * @param row 行数据
     * @param col 列名
     * @return 命中值，未命中返回 null
     */
    private static Object findIgnoreCase(Map<String, Object> row, String col) {
        if (row == null || col == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (col.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
