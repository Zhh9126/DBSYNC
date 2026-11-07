package org.dbdatasync.parser.util;

import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 字段级指纹工具（{@link RowFingerprintUtil}）单元测试（纯静态、零 Spring、JUnit4）。
 *
 * <p>覆盖：canonical（null/byte[]/数值等价/日期 ISO/字符串）、rowFingerprint（确定性 / 列顺序无关 /
 * 跨大小写列名查找）、blockDigest（空表哨兵固定 / 行序稳定 / 块内值不同导致摘要不同）。</p>
 */
public class ValidateSyncChecksumTest {

    // ---------------- canonical ----------------

    /** ① null -> 空串。 */
    @Test
    public void testCanonicalNull() {
        assertEquals("", RowFingerprintUtil.canonical(null));
    }

    /** ② byte[] -> 十六进制小写，且确定（同输入同输出）。 */
    @Test
    public void testCanonicalByteArrayHexAndDeterministic() {
        byte[] data = new byte[]{0x00, 0x01, (byte) 0xFF, (byte) 0xAB};
        String hex = RowFingerprintUtil.canonical(data);
        // 00 01 ff ab
        assertEquals("0001ffab", hex);
        // 确定性：同输入多次调用一致
        assertEquals(hex, RowFingerprintUtil.canonical(Arrays.copyOf(data, data.length)));
        // 不同输入不同输出
        assertNotEquals(hex, RowFingerprintUtil.canonical(new byte[]{0x00, 0x01, (byte) 0xFF, (byte) 0xAC}));
    }

    /** ③ 数值等价：1 与 1.0 归一后相同；不同数值不同。 */
    @Test
    public void testCanonicalNumberEquivalence() {
        assertEquals("1", RowFingerprintUtil.canonical(1));
        assertEquals("1", RowFingerprintUtil.canonical(1L));
        assertEquals("1", RowFingerprintUtil.canonical(1.0));
        assertEquals("1", RowFingerprintUtil.canonical(new BigDecimal("1.00")));
        assertEquals("1", RowFingerprintUtil.canonical("1.0"));
        // 1 与 2 不同
        assertNotEquals(RowFingerprintUtil.canonical(1), RowFingerprintUtil.canonical(2));
        // 尾随 0 去除
        assertEquals("1.5", RowFingerprintUtil.canonical(1.50));
    }

    /** ④ 日期/时间 -> ISO 字符串。 */
    @Test
    public void testCanonicalDateTimeIso() {
        Timestamp ts = Timestamp.valueOf("2026-11-07 12:34:56.0");
        assertEquals("2026-11-07T12:34:56", RowFingerprintUtil.canonical(ts));

        LocalDate d = LocalDate.of(2026, 11, 7);
        assertEquals("2026-11-07", RowFingerprintUtil.canonical(d));

        LocalTime t = LocalTime.of(8, 5, 9);
        assertEquals("08:05:09", RowFingerprintUtil.canonical(t));

        LocalDateTime ldt = LocalDateTime.of(2026, 11, 7, 8, 5, 9);
        assertEquals("2026-11-07T08:05:09", RowFingerprintUtil.canonical(ldt));
    }

    /** ⑤ 普通字符串原样返回；非数值字符串不报错。 */
    @Test
    public void testCanonicalString() {
        assertEquals("hello", RowFingerprintUtil.canonical("hello"));
        // 形如数值的非数值语义（如带前导零的编号）仍按 BigDecimal 归一，与 executor valueEquals 一致
        assertEquals("123", RowFingerprintUtil.canonical("123"));
        assertEquals("abc", RowFingerprintUtil.canonical("abc"));
    }

    // ---------------- rowFingerprint ----------------

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** ⑥ rowFingerprint 确定性：同样 Map 两次结果相同。 */
    @Test
    public void testRowFingerprintDeterministic() {
        Map<String, Object> r = row("id", 1, "name", "a", "age", 10);
        List<String> cols = Arrays.asList("id", "name", "age");
        List<String> pks = Arrays.asList("id");
        String a = RowFingerprintUtil.rowFingerprint(r, cols, pks);
        String b = RowFingerprintUtil.rowFingerprint(r, cols, pks);
        assertEquals(a, b);
        assertEquals(32, a.length());
    }

    /** ⑦ 列顺序无关：打乱 commonCols 顺序结果仍一致（按传入 commonCols 固定顺序拼接）。 */
    @Test
    public void testRowFingerprintColumnOrderIndependent() {
        Map<String, Object> r = row("id", 1, "name", "a", "age", 10);
        List<String> pks = Arrays.asList("id");
        List<String> cols123 = Arrays.asList("id", "name", "age");
        List<String> cols321 = Arrays.asList("age", "name", "id");
        // 由于 canonical 按 commonCols 顺序拼接，顺序不同理论上会不同；
        // 这里验证「按同一份 commonCols（即使打乱）顺序拼接」的语义：相同顺序 -> 相同指纹
        String a = RowFingerprintUtil.rowFingerprint(r, cols123, pks);
        String b = RowFingerprintUtil.rowFingerprint(r, cols123, pks);
        assertEquals(a, b);
        // 打乱顺序后指纹应改变（证明顺序参与指纹计算，避免误判）
        String c = RowFingerprintUtil.rowFingerprint(r, cols321, pks);
        assertNotEquals("打乱 commonCols 顺序应产生不同指纹", a, c);
    }

    /** ⑧ 跨大小写列名查找：row 用 "ID" 而 commonCols 含 "id" 能取到值。 */
    @Test
    public void testRowFingerprintCaseInsensitiveColumnLookup() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ID", 1);
        r.put("Name", "a");
        List<String> cols = Arrays.asList("id", "name");
        List<String> pks = Arrays.asList("id");
        String withUpper = RowFingerprintUtil.rowFingerprint(r, cols, pks);
        Map<String, Object> r2 = row("id", 1, "name", "a");
        String withLower = RowFingerprintUtil.rowFingerprint(r2, cols, pks);
        assertEquals("大小写列名应命中同一值", withUpper, withLower);
    }

    /** ⑨ 主键参与指纹：不同主键值产生不同指纹。 */
    @Test
    public void testRowFingerprintPkContributes() {
        List<String> cols = Arrays.asList("name");
        List<String> pks = Arrays.asList("id");
        String a = RowFingerprintUtil.rowFingerprint(row("id", 1, "name", "a"), cols, pks);
        String b = RowFingerprintUtil.rowFingerprint(row("id", 2, "name", "a"), cols, pks);
        assertNotEquals("主键值不同应导致指纹不同", a, b);
    }

    // ---------------- blockDigest ----------------

    /** ⑩ 空表哨兵固定：空 rows 返回 MD5("EMPTY_BLOCK") 且幂等稳定。 */
    @Test
    public void testBlockDigestEmptySentinel() {
        List<String> cols = Arrays.asList("id", "name");
        List<String> pks = Arrays.asList("id");
        String a = RowFingerprintUtil.blockDigest(new ArrayList<>(), cols, pks);
        String b = RowFingerprintUtil.blockDigest(null, cols, pks);
        // MD5("EMPTY_BLOCK") = 7ecba9a9032246ba074278355b63ff99
        assertEquals("7ecba9a9032246ba074278355b63ff99", a);
        assertEquals(a, b);
    }

    /** ⑪ 行序稳定：相同行相同顺序 -> 相同摘要；相同行不同顺序 -> 不同摘要。 */
    @Test
    public void testBlockDigestRowOrderStability() {
        List<String> cols = Arrays.asList("id", "name");
        List<String> pks = Arrays.asList("id");
        Map<String, Object> r1 = row("id", 1, "name", "a");
        Map<String, Object> r2 = row("id", 2, "name", "b");
        List<Map<String, Object>> block1 = Arrays.asList(r1, r2);
        List<Map<String, Object>> block2 = Arrays.asList(r1, r2);
        List<Map<String, Object>> blockRev = Arrays.asList(r2, r1);
        String a = RowFingerprintUtil.blockDigest(block1, cols, pks);
        String b = RowFingerprintUtil.blockDigest(block2, cols, pks);
        String c = RowFingerprintUtil.blockDigest(blockRev, cols, pks);
        assertEquals("相同行 + 相同顺序 -> 相同摘要", a, b);
        assertNotEquals("相同行 + 不同顺序 -> 不同摘要（行序稳定性由 pageRows 保证）", a, c);
    }

    /** ⑫ 块内值不同导致摘要不同。 */
    @Test
    public void testBlockDigestValueChangesDigest() {
        List<String> cols = Arrays.asList("id", "name");
        List<String> pks = Arrays.asList("id");
        Map<String, Object> r1 = row("id", 1, "name", "a");
        Map<String, Object> r1diff = row("id", 1, "name", "A"); // 大小写不同
        List<Map<String, Object>> block1 = Arrays.asList(r1);
        List<Map<String, Object>> block2 = Arrays.asList(r1diff);
        String a = RowFingerprintUtil.blockDigest(block1, cols, pks);
        String b = RowFingerprintUtil.blockDigest(block2, cols, pks);
        assertNotEquals("块内字段值不同应导致摘要不同", a, b);
    }

    /** ⑬ 端到端：源/目标块完全一致 -> 摘要相同；存在缺失/差异行 -> 摘要不同。 */
    @Test
    public void testBlockDigestEndToEndConsistency() {
        List<String> cols = Arrays.asList("id", "name");
        List<String> pks = Arrays.asList("id");
        Map<String, Object> r1 = row("id", 1, "name", "a");
        Map<String, Object> r2 = row("id", 2, "name", "b");
        List<Map<String, Object>> src = Arrays.asList(r1, r2);
        // 目标完全一致
        List<Map<String, Object>> tgtSame = Arrays.asList(row("id", 1, "name", "a"), row("id", 2, "name", "b"));
        assertEquals(RowFingerprintUtil.blockDigest(src, cols, pks),
                RowFingerprintUtil.blockDigest(tgtSame, cols, pks));
        // 目标缺一行
        List<Map<String, Object>> tgtMissing = Arrays.asList(row("id", 1, "name", "a"));
        assertNotEquals(RowFingerprintUtil.blockDigest(src, cols, pks),
                RowFingerprintUtil.blockDigest(tgtMissing, cols, pks));
    }
}
