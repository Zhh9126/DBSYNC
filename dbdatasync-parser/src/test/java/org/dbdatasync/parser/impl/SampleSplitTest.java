package org.dbdatasync.parser.impl;

import org.dbdatasync.parser.impl.ParserComponentImpl.SplitBound;
import org.dbdatasync.parser.model.Mapping;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave32 采样均分切片核心逻辑单元测试。
 *
 * <p>覆盖要点：</p>
 * <ul>
 *     <li>buildSampleIntervals：断点升序映射区间、无缝拼接、最后一片闭到 max、断点去重合并窄片。</li>
 *     <li>injectRangeCondition(Object)：uuid/字符串/日期原值参数化（SQL 占位符不变、值不内嵌）。</li>
 *     <li>parseSplitMode / parseSampleCap：非法回退与上下限收敛。</li>
 *     <li>intervalBoundsValue：Wave31 数值格式不变 + 采样原值字符串格式。</li>
 * </ul>
 */
public class SampleSplitTest {

    private final ParserComponentImpl impl = new ParserComponentImpl();

    // ---------- buildSampleIntervals ----------

    @Test
    public void testSampleIntervalsBasicCoverage() {
        // 断点 [25, 50, 75]，值域 [1, 100]
        Object[] bounds = {25L, 50L, 75L};
        SplitBound[] intervals = impl.buildSampleIntervals(bounds, 1L, 100L);
        assertEquals(4, intervals.length);
        assertEquals(1L, intervals[0].getLower());
        assertEquals(25L, intervals[0].getUpper());
        assertEquals(25L, intervals[1].getLower());
        assertEquals(50L, intervals[1].getUpper());
        assertEquals(50L, intervals[2].getLower());
        assertEquals(75L, intervals[2].getUpper());
        assertEquals(75L, intervals[3].getLower());
        assertEquals(100L, intervals[3].getUpper()); // 最后一片闭到 max（探测值）
    }

    @Test
    public void testSampleIntervalsMergeDedup() {
        // 断点重复（同键多行）：25 出现两次，应合并
        Object[] bounds = {25L, 25L, 50L};
        SplitBound[] intervals = impl.buildSampleIntervals(bounds, 1L, 100L);
        assertEquals(3, intervals.length);
        assertEquals(1L, intervals[0].getLower());
        assertEquals(25L, intervals[0].getUpper());
        assertEquals(25L, intervals[1].getLower());
        assertEquals(50L, intervals[1].getUpper());
        assertEquals(50L, intervals[2].getLower());
        assertEquals(100L, intervals[2].getUpper());
    }

    @Test
    public void testSampleIntervalsUuidString() {
        // uuid 字符串断点（按字符串序升序）
        Object[] bounds = {"aaaa0000-1111", "bbbb0000-2222"};
        SplitBound[] intervals = impl.buildSampleIntervals(bounds, "0000aaaa", "ffffffff");
        assertEquals(3, intervals.length);
        assertEquals("0000aaaa", intervals[0].getLower());
        assertEquals("aaaa0000-1111", intervals[0].getUpper());
        assertEquals("aaaa0000-1111", intervals[1].getLower());
        assertEquals("bbbb0000-2222", intervals[1].getUpper());
        assertEquals("bbbb0000-2222", intervals[2].getLower());
        assertEquals("ffffffff", intervals[2].getUpper());
    }

    @Test
    public void testSampleIntervalsEmptyBounds() {
        SplitBound[] intervals = impl.buildSampleIntervals(null, 1L, 100L);
        assertEquals(0, intervals.length);
        SplitBound[] intervals2 = impl.buildSampleIntervals(new Object[0], 1L, 100L);
        assertEquals(0, intervals2.length);
    }

    @Test
    public void testSampleIntervalsAllSameValue() {
        // 所有断点都等于 min（键全同值）：只保留一片 [min, max]
        Object[] bounds = {5L, 5L};
        SplitBound[] intervals = impl.buildSampleIntervals(bounds, 5L, 5L);
        assertEquals(1, intervals.length);
        assertEquals(5L, intervals[0].getLower());
        assertEquals(5L, intervals[0].getUpper());
    }

    // ---------- injectRangeCondition(Object) ----------

    @Test
    public void testInjectWithUuidRawString() {
        String sql = "SELECT * FROM t_user WHERE status = ? ORDER BY id LIMIT ?";
        // uuid 原值：SQL 不内嵌值，恒占位符
        String injected = impl.injectRangeCondition(sql, "u_id",
                "0000aaaa-1111-2222-3333-444455556666", "bbbbcccc-1111-2222-3333-444455556666",
                false, false);
        assertTrue(injected.contains("WHERE (u_id >= ? AND u_id < ?) AND status = ?"));
        assertFalse("uuid 原值不得内嵌进 SQL 文本", injected.contains("0000aaaa-1111"));
    }

    @Test
    public void testInjectWithDateRawString() {
        String sql = "SELECT * FROM t WHERE created_at > ?";
        String injected = impl.injectRangeCondition(sql, "created_at",
                "2026-01-01 00:00:00", "2026-06-01 00:00:00", false, false);
        assertEquals("SELECT * FROM t WHERE (created_at >= ? AND created_at < ?) AND created_at > ?", injected);
    }

    @Test
    public void testInjectSampleLastSliceInclusive() {
        String sql = "SELECT * FROM t";
        String injected = impl.injectRangeCondition(sql, "u_id",
                "bbbbcccc-1111", "ffffffff", false, true);
        assertEquals("SELECT * FROM t WHERE (u_id >= ? AND u_id <= ?)", injected);
    }

    @Test
    public void testInjectFirstSliceIncludesNullRowsWithUuid() {
        String sql = "SELECT * FROM t";
        String injected = impl.injectRangeCondition(sql, "u_id",
                "0000aaaa", "bbbbcccc", true, false);
        assertTrue(injected.contains("u_id IS NULL OR (u_id >= ? AND u_id < ?)"));
    }

    // ---------- parseSplitMode / parseSampleCap ----------

    @Test
    public void testParseSplitMode() {
        assertEquals("value", impl.parseSplitMode(mappingWithParams(null)));
        assertEquals("value", impl.parseSplitMode(mappingWithParams(new HashMap<>())));

        Map<String, String> params = new HashMap<>();
        params.put("fullSplitMode", "sample");
        assertEquals("sample", impl.parseSplitMode(mappingWithParams(params)));

        params.put("fullSplitMode", "SAMPLE");
        assertEquals("sample", impl.parseSplitMode(mappingWithParams(params)));

        params.put("fullSplitMode", "xml;evil");
        assertEquals("value", impl.parseSplitMode(mappingWithParams(params)));
    }

    @Test
    public void testParseSampleCap() {
        // 默认 64
        assertEquals(64, impl.parseSampleCap(mappingWithParams(null)));
        Map<String, String> params = new HashMap<>();
        assertEquals(64, impl.parseSampleCap(mappingWithParams(params)));
        // 非法 / 小于 2 回退 64
        params.put("fullSplitSampleCap", "abc");
        assertEquals(64, impl.parseSampleCap(mappingWithParams(params)));
        params.put("fullSplitSampleCap", "1");
        assertEquals(64, impl.parseSampleCap(mappingWithParams(params)));
        // 正常值
        params.put("fullSplitSampleCap", "16");
        assertEquals(16, impl.parseSampleCap(mappingWithParams(params)));
        // 超上限收敛
        params.put("fullSplitSampleCap", "99999");
        assertEquals(4096, impl.parseSampleCap(mappingWithParams(params)));
        // 浮点非法
        params.put("fullSplitSampleCap", "4.5");
        assertEquals(64, impl.parseSampleCap(mappingWithParams(params)));
    }

    // ---------- intervalBounds / intervalBoundsValue ----------

    @Test
    public void testIntervalBoundsNumericBackwardCompatible() {
        // Wave31 数值格式 start~end 完全不变
        assertEquals("10~20", ParserComponentImpl.intervalBounds(10L, 20L));
        assertEquals("0~9223372036854775807", ParserComponentImpl.intervalBounds(0L, Long.MAX_VALUE));
    }

    @Test
    public void testIntervalBoundsSampleRawString() {
        assertEquals("0000aaaa~bbbbcccc", ParserComponentImpl.intervalBounds("0000aaaa", "bbbbcccc"));
        assertEquals("0000aaaa~", ParserComponentImpl.intervalBounds("0000aaaa", null));
    }

    private Mapping mappingWithParams(Map<String, String> params) {
        Mapping mapping = new Mapping();
        mapping.setParams(params);
        return mapping;
    }
}
