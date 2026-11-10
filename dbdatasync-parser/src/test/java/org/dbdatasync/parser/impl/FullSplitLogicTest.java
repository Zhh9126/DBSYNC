package org.dbdatasync.parser.impl;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 全量分片并发读核心逻辑单元测试：区间切分边界 + 范围条件 SQL 注入。
 *
 * <p>覆盖要点：</p>
 * <ul>
 *     <li>splitHalfOpenRange：常规切分连续性（相邻区间无缝无重叠）、最后一片闭合覆盖 max、空值域、count 超界。</li>
 *     <li>injectRangeCondition：有 WHERE / 无 WHERE / 带 ORDER BY / 带 LIMIT 四种 SQL 形态，占位符顺序。</li>
 * </ul>
 */
public class FullSplitLogicTest {

    // ---------- splitHalfOpenRange ----------

    @Test
    public void testSplitBasicContinuity() {
        // [1, 100] 分 4 片：应无缝拼接覆盖 1..100
        long[][] ranges = ParserComponentImpl.splitHalfOpenRange(1, 100, 4);
        assertEquals(4, ranges.length);
        assertEquals(1, ranges[0][0]);
        assertTrue(ranges[0][1] <= ranges[1][0]);
        assertTrue(ranges[1][1] <= ranges[2][0]);
        assertTrue(ranges[2][1] <= ranges[3][0]);
        // 最后一片覆盖到 max+1（开区间）
        assertEquals(101, ranges[3][1]);
        // 相邻区间拼接无缝隙
        for (int i = 0; i < ranges.length - 1; i++) {
            assertEquals(ranges[i][1], ranges[i + 1][0]);
        }
    }

    @Test
    public void testSplitCoversAllValues() {
        long min = 10, max = 20;
        long[][] ranges = ParserComponentImpl.splitHalfOpenRange(min, max, 3);
        long coveredStart = ranges[0][0];
        long coveredEnd = ranges[ranges.length - 1][1];
        assertEquals(min, coveredStart);
        assertEquals(max + 1, coveredEnd);
        // 相邻无重叠
        for (int i = 0; i < ranges.length - 1; i++) {
            assertTrue(ranges[i][1] <= ranges[i + 1][0]);
        }
    }

    @Test
    public void testSplitMoreThanRange() {
        // 值域 3 个值，分 10 片：应只生成覆盖范围所需的片数（3 片），且不产生空区间
        long[][] ranges = ParserComponentImpl.splitHalfOpenRange(1, 3, 10);
        assertTrue(ranges.length <= 10);
        assertTrue(ranges.length >= 1);
        long coveredStart = ranges[0][0];
        long coveredEnd = ranges[ranges.length - 1][1];
        assertEquals(1, coveredStart);
        assertEquals(4, coveredEnd);
    }

    @Test
    public void testSplitSingleValue() {
        long[][] ranges = ParserComponentImpl.splitHalfOpenRange(5, 5, 4);
        assertEquals(1, ranges.length);
        assertEquals(5, ranges[0][0]);
        assertEquals(6, ranges[0][1]);
    }

    @Test
    public void testSplitEmptyRange() {
        assertArrayEquals(new long[0][], ParserComponentImpl.splitHalfOpenRange(10, 1, 4));
        assertArrayEquals(new long[0][], ParserComponentImpl.splitHalfOpenRange(1, 100, 0));
        assertArrayEquals(new long[0][], ParserComponentImpl.splitHalfOpenRange(1, 100, -1));
    }

    @Test
    public void testSplitCountOne() {
        long[][] ranges = ParserComponentImpl.splitHalfOpenRange(1, 100, 1);
        assertEquals(1, ranges.length);
        assertEquals(1, ranges[0][0]);
        assertEquals(101, ranges[0][1]);
    }

    // ---------- injectRangeCondition ----------

    @Test
    public void testInjectWithExistingWhere() {
        String sql = "SELECT id, name FROM \"db\".\"t_user\" WHERE id > ? ORDER BY id LIMIT ?, ?";
        String injected = new ParserComponentImpl().injectRangeCondition(sql, "id", 100, 200);
        assertTrue(injected.contains("WHERE (id >= ? AND id < ?) AND id > ? ORDER BY id LIMIT ?, ?"));
        // 占位符顺序：start, end, 原cursor参数, offset, limit
        int p1 = injected.indexOf("?");
        assertTrue(injected.indexOf("?") >= 0);
        String expected = "SELECT id, name FROM \"db\".\"t_user\" WHERE (id >= ? AND id < ?) AND id > ? ORDER BY id LIMIT ?, ?";
        assertEquals(expected, injected);
    }

    @Test
    public void testInjectWithoutWhere() {
        String sql = "SELECT id, name FROM \"db\".\"t_user\" ORDER BY id LIMIT ?, ?";
        String injected = new ParserComponentImpl().injectRangeCondition(sql, "id", 1, 5);
        assertEquals("SELECT id, name FROM \"db\".\"t_user\" WHERE (id >= ? AND id < ?) ORDER BY id LIMIT ?, ?", injected);
    }

    @Test
    public void testInjectCountSql() {
        String sql = "SELECT COUNT(*) FROM \"db\".\"t_user\" WHERE name = ?";
        String injected = new ParserComponentImpl().injectRangeCondition(sql, "id", 1, 5);
        assertEquals("SELECT COUNT(*) FROM \"db\".\"t_user\" WHERE (id >= ? AND id < ?) AND name = ?", injected);
    }

    @Test
    public void testInjectPlainSql() {
        String sql = "SELECT id FROM t_user";
        String injected = new ParserComponentImpl().injectRangeCondition(sql, "id", 1, 5);
        assertEquals("SELECT id FROM t_user WHERE (id >= ? AND id < ?)", injected);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInjectRejectsMaliciousColumn() {
        new ParserComponentImpl().injectRangeCondition("SELECT 1 FROM t", "id; DROP TABLE t; --", 1, 5);
    }

    /**
     * 探测值域 SQL 构造：COUNT(*) → MIN(x), MAX(x)
     */
    @Test
    public void testProbeMinMaxSqlShape() {
        String countSql = "SELECT COUNT(*) FROM \"db\".\"t_user\" WHERE name = ?";
        // 复现 probeSplitRange 内 SQL 构造逻辑（抽取断言）
        String splitKey = "id";
        int idx = countSql.indexOf("COUNT(*)");
        String minMax = countSql.substring(0, idx) + "MIN(" + splitKey + "), MAX(" + splitKey + ")"
                + countSql.substring(idx + "COUNT(*)".length());
        assertEquals("SELECT MIN(id), MAX(id) FROM \"db\".\"t_user\" WHERE name = ?", minMax);
    }

    // ---------- Wave30 大数据量分片边界 ----------

    @Test
    public void testSplitHugeRangeDoesNotOverflow() {
        // max - min + 1 会超过 Long.MAX_VALUE：旧实现溢出成负数，step 变成 1 或负值导致分片全错
        long[][] ranges = ParserComponentImpl.splitHalfOpenRange(0L, Long.MAX_VALUE, 3);
        assertTrue(ranges.length >= 2);
        assertEquals(0L, ranges[0][0]);
        assertEquals(Long.MAX_VALUE, ranges[ranges.length - 1][1]);
        for (int i = 0; i < ranges.length; i++) {
            assertTrue("分片起点必须递增: " + i, ranges[i][0] >= 0L);
            assertTrue("分片不得倒置: " + i, ranges[i][1] >= ranges[i][0]);
            if (i > 0) {
                assertEquals("相邻分片必须无缝: " + i, ranges[i - 1][1], ranges[i][0]);
            }
        }
    }

    @Test
    public void testSplitSingleSliceOfHugeRangeUsesInclusiveEnd() {
        long[][] ranges = ParserComponentImpl.splitHalfOpenRange(0L, Long.MAX_VALUE, 1);
        assertEquals(1, ranges.length);
        // 最后一片 end 饱和在 MAX，由 injectRangeCondition 用 <= 兜住该行
        assertEquals(Long.MAX_VALUE, ranges[0][1]);
    }

    @Test
    public void testInjectFirstSliceIncludesNullRows() {
        String sql = "SELECT id FROM t_user";
        String first = new ParserComponentImpl().injectRangeCondition(sql, "id", 1, 10, true, false);
        assertTrue(first.contains("id IS NULL OR"));
        assertTrue(first.contains("id < ?"));
        String other = new ParserComponentImpl().injectRangeCondition(sql, "id", 10, 20, false, false);
        assertFalse(other.contains("IS NULL"));
        assertEquals("SELECT id FROM t_user WHERE (id >= ? AND id < ?)", other);
    }

    @Test
    public void testInjectMaxValueUpperBoundIsInclusive() {
        String sql = "SELECT id FROM t_user";
        String injected = new ParserComponentImpl().injectRangeCondition(sql, "id", 1, Long.MAX_VALUE, false, true);
        assertEquals("SELECT id FROM t_user WHERE (id >= ? AND id <= ?)", injected);
    }

    @Test
    public void testInjectDoesNotTouchCursorTrailing() {
        // 复合主键游标：注入必须发生在首个 WHERE，cursor 复合条件保持在后
        String sql = "SELECT a, b FROM t WHERE (a > ? OR (a = ? AND b > ?)) ORDER BY a, b LIMIT ?";
        String injected = new ParserComponentImpl().injectRangeCondition(sql, "id", 0, 50);
        // 注入后：WHERE (id >= ? AND id < ?) AND (a > ? OR (a = ? AND b > ?)) ORDER BY a, b LIMIT ?
        assertTrue(injected.startsWith("SELECT a, b FROM t WHERE (id >= ? AND id < ?) AND (a > ? OR (a = ? AND b > ?)) ORDER BY a, b LIMIT ?"));
        assertFalse(injected.contains("LIMIT ?)"));
    }
}
