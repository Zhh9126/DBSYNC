package org.dbdatasync.parser.impl;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 订正校验执行器 Wave5 新增逻辑单元测试：
 * 快速体检/分片并发校验的模式判定与区间切片。
 *
 * <p>覆盖：</p>
 * <ul>
 *     <li>fullCompareMode：空白/非法值回退 full、health/sample/full 归一化（大小写不敏感）；</li>
 *     <li>sliceable：单列主键 + fullSplitCount&gt;1 才启用；多列主键/无主键/非法分片数禁用（存量零影响）；</li>
 *     <li>sliceRanges：复用 Wave4 splitHalfOpenRange 的边界语义（无缝拼接、覆盖 max+1）。</li>
 * </ul>
 */
public class ValidateSyncExecutorTest {

    // ---------- fullCompareMode ----------

    @Test
    public void testModeNormalize() {
        assertEquals("full", ValidateSyncExecutor.fullCompareMode(null));
        assertEquals("full", ValidateSyncExecutor.fullCompareMode(""));
        assertEquals("full", ValidateSyncExecutor.fullCompareMode("  "));
        // 大小写不敏感
        assertEquals("full", ValidateSyncExecutor.fullCompareMode("FULL"));
        assertEquals("sample", ValidateSyncExecutor.fullCompareMode("SamPle"));
        assertEquals("health", ValidateSyncExecutor.fullCompareMode("HEALTH"));
        // Wave13: checksum 模式归一化（大小写不敏感）
        assertEquals("checksum", ValidateSyncExecutor.fullCompareMode("CHECKSUM"));
        assertEquals("checksum", ValidateSyncExecutor.fullCompareMode("checksum"));
        // 非法值回退 full（存量任务兼容）
        assertEquals("full", ValidateSyncExecutor.fullCompareMode("unknown"));
        assertEquals("full", ValidateSyncExecutor.fullCompareMode("bogusXYZ"));
    }

    // ---------- sliceable ----------

    @Test
    public void testSliceableSinglePk() {
        // 单列主键 + 分片数 >1 → 启用
        assertTrue(ValidateSyncExecutor.sliceable(1, "4"));
    }

    @Test
    public void testSliceableNotEnabled() {
        // 分片数默认 0（未配置）→ 不启用（存量任务零影响）
        assertFalse(ValidateSyncExecutor.sliceable(1, "0"));
        // 空/非法分片数 → 不启用
        assertFalse(ValidateSyncExecutor.sliceable(1, null));
        assertFalse(ValidateSyncExecutor.sliceable(1, ""));
        assertFalse(ValidateSyncExecutor.sliceable(1, "abc"));
        assertFalse(ValidateSyncExecutor.sliceable(1, "-1"));
    }

    @Test
    public void testSliceableNoPk() {
        // 无主键（0 列）或多列主键 → 不启用
        assertFalse(ValidateSyncExecutor.sliceable(0, "4"));
        assertFalse(ValidateSyncExecutor.sliceable(2, "4"));
    }

    // ---------- sliceRanges（复用 splitHalfOpenRange 语义） ----------

    @Test
    public void testSliceRangesContinuity() {
        long[][] ranges = ValidateSyncExecutor.sliceRanges(1, 100, 4);
        assertEquals(4, ranges.length);
        assertEquals(1, ranges[0][0]);
        // 最后一片开区间覆盖到 max+1
        assertEquals(101, ranges[ranges.length - 1][1]);
        // 相邻区间无缝拼接
        for (int i = 0; i < ranges.length - 1; i++) {
            assertEquals(ranges[i][1], ranges[i + 1][0]);
        }
    }

    @Test
    public void testSliceRangesSingleValue() {
        long[][] ranges = ValidateSyncExecutor.sliceRanges(5, 5, 4);
        assertEquals(1, ranges.length);
        assertArrayEquals(new long[]{5, 6}, ranges[0]);
    }

    @Test
    public void testSliceRangesEmptyRange() {
        // 空值域 → 空数组（调用方据此降级串行）
        assertArrayEquals(new long[0][], ValidateSyncExecutor.sliceRanges(10, 1, 4));
    }

    @Test
    public void testSliceRangesCountOne() {
        long[][] ranges = ValidateSyncExecutor.sliceRanges(1, 3, 1);
        assertEquals(1, ranges.length);
        assertArrayEquals(new long[]{1, 4}, ranges[0]);
    }
}
