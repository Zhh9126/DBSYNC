package org.dbdatasync.biz.qa;

import org.dbdatasync.biz.service.impl.DatabaseSyncPerfAdviceServiceImpl;
import org.dbdatasync.biz.vo.DatabaseSyncPerfAdviceVO;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave11 方向五「成本 / 耗时 / 吞吐统计报告」纯派生展示层回归用例。
 *
 * <p>只通过反射调用 {@code DatabaseSyncPerfAdviceServiceImpl#fillCostMetrics}，
 * 用真实 VO + 动态构造的 DatabaseSyncTask 桩验证 7 个派生字段；
 * 不连库、不启服务、不写 Meta、不改任何生产源码。
 * 重点证明本层为「纯派生」：调用前后 Wave10 Q1 已固化的
 * totalRows / successRows / failRows / metaTotal / rowsConsistent /
 * durationMs / throughputPerSecond 完全不变。</p>
 *
 * <p>JUnit 4；参考 Wave10QaEdgeCaseTest 的反射调用私有方法与反射造桩写法。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class Wave11CostMetricsTest {

    private static final double DELTA = 0.0001D;

    // ==================== ① 未限速：理论最短耗时 = 0 ====================

    @Test
    public void unlimited_estimatedMinDurationIsZero() {
        DatabaseSyncPerfAdviceVO vo = buildVo(5000L, 5000L, 3200L, 1562.5D);
        fill(vo, task(0L, 0L, false, "", ""));

        assertEquals(0L, vo.getConfiguredRecordsPerSecond());
        assertEquals(0L, vo.getConfiguredBytesPerSecond());
        assertEquals(0L, vo.getEstimatedMinDurationMs());
    }

    // ==================== ② 限速 1000 行/s + 5000 行 → 5000 ms ====================

    @Test
    public void limited_1000RowsPerSecond_5000Rows_estimates5000Ms() {
        DatabaseSyncPerfAdviceVO vo = buildVo(5000L, 5000L, 3200L, 1562.5D);
        fill(vo, task(1000L, 0L, false, "", ""));

        assertEquals(1000L, vo.getConfiguredRecordsPerSecond());
        assertEquals(5000L, vo.getEstimatedMinDurationMs());
    }

    /** 额外证明 Math.ceil 真的向上取整（非整除商） */
    @Test
    public void estimatedMinDuration_roundsUpOnFraction() {
        // totalRows=1、限速 3 行/s → 1*1000/3 = 333.33… → ceil = 334 ms
        DatabaseSyncPerfAdviceVO vo = buildVo(1L, 1L, 1000L, 1.0D);
        fill(vo, task(3L, 0L, false, "", ""));

        assertEquals(334L, vo.getEstimatedMinDurationMs());
    }

    // ==================== ③ duration<=0 → avg=0，绝不除零 ====================

    @Test
    public void durationNonPositive_avgIsZero_noDivideByZero() {
        // durationMs = 0
        DatabaseSyncPerfAdviceVO zero = buildVo(5000L, 5000L, 0L, 0D);
        fill(zero, task(1000L, 2048L, false, "", ""));
        assertEquals(0.0D, zero.getAvgMsPerThousandRows(), DELTA);

        // durationMs < 0（analyze() 内已被 max(0,…) 归一，此处直接喂负边界验证不除零）
        DatabaseSyncPerfAdviceVO negative = buildVo(5000L, 5000L, -100L, 0D);
        fill(negative, task(1000L, 2048L, false, "", ""));
        assertEquals(0.0D, negative.getAvgMsPerThousandRows(), DELTA);
    }

    // ==================== ④ successRows=0 → avg=0 ====================

    @Test
    public void zeroSuccessRows_avgIsZero() {
        DatabaseSyncPerfAdviceVO vo = buildVo(5000L, 0L, 3200L, 0D);
        fill(vo, task(1000L, 0L, false, "", ""));

        assertEquals(0.0D, vo.getAvgMsPerThousandRows(), DELTA);
    }

    // ==================== ⑤ 正常值 avg 精度（保留 1 位小数） ====================

    @Test
    public void avgMsPerThousandRows_roundToOneDecimal() {
        // 3200 ms / 12000 行 * 1000 = 266.6666… → 266.7
        DatabaseSyncPerfAdviceVO vo = buildVo(12000L, 12000L, 3200L, 3750.0D);
        fill(vo, task(0L, 0L, false, "", ""));
        assertEquals(266.7D, vo.getAvgMsPerThousandRows(), DELTA);

        // 1 ms / 3 行 * 1000 = 333.3333… → 333.3
        DatabaseSyncPerfAdviceVO small = buildVo(3L, 3L, 1L, 3000.0D);
        fill(small, task(0L, 0L, false, "", ""));
        assertEquals(333.3D, small.getAvgMsPerThousandRows(), DELTA);
    }

    // ==================== ⑥ task=null 全字段安全、不 NPE ====================

    @Test
    public void nullTask_allFieldsSafe_noNpe() {
        DatabaseSyncPerfAdviceVO vo = buildVo(5000L, 5000L, 3200L, 1562.5D);
        fill(vo, null);

        assertEquals(0L, vo.getConfiguredRecordsPerSecond());
        assertEquals(0L, vo.getConfiguredBytesPerSecond());
        assertFalse(vo.isOffPeakWindowEnabled());
        assertEquals("未启用", vo.getOffPeakWindowText());
        assertEquals(0L, vo.getEstimatedMinDurationMs());
        // 派生量仍由 vo 既有口径算出（与 task 是否为空无关）
        assertEquals(640.0D, vo.getAvgMsPerThousandRows(), DELTA);
        assertNotNull(vo.getCostSummary());
        assertTrue(vo.getCostSummary().contains("未配置限速"));
    }

    // ==================== ⑦ 时间窗三种文案 ====================

    @Test
    public void offPeakWindowText_threeVariants() {
        // 启用 + 起止完整
        DatabaseSyncPerfAdviceVO enabled = buildVo(0L, 0L, 0L, 0D);
        fill(enabled, task(0L, 0L, true, "22:00", "06:00"));
        assertTrue(enabled.isOffPeakWindowEnabled());
        assertEquals("22:00 ~ 06:00", enabled.getOffPeakWindowText());

        // 未启用
        DatabaseSyncPerfAdviceVO disabled = buildVo(0L, 0L, 0L, 0D);
        fill(disabled, task(0L, 0L, false, "22:00", "06:00"));
        assertFalse(disabled.isOffPeakWindowEnabled());
        assertEquals("未启用", disabled.getOffPeakWindowText());

        // 启用但配置缺失（end 为空）
        DatabaseSyncPerfAdviceVO missing = buildVo(0L, 0L, 0L, 0D);
        fill(missing, task(0L, 0L, true, "22:00", ""));
        assertTrue(missing.isOffPeakWindowEnabled());
        assertEquals("未配置", missing.getOffPeakWindowText());
    }

    // ==================== ⑧ 纯派生：Wave10 Q1 口径完全不变 ====================

    @Test
    public void fillCostMetrics_doesNotMutateWave10Fields() {
        DatabaseSyncPerfAdviceVO vo = buildVo(12345L, 12000L, 3200L, 3750.0D);
        vo.setFailRows(345L);
        vo.setMetaTotal(9999L);          // 故意与 success+fail 不一致
        vo.setRowsConsistent(false);
        vo.setMigrated(true);

        // 调用前快照（Wave10 已固化的 7 个字段）
        long totalRows = vo.getTotalRows();
        long successRows = vo.getSuccessRows();
        long failRows = vo.getFailRows();
        long metaTotal = vo.getMetaTotal();
        boolean rowsConsistent = vo.isRowsConsistent();
        long durationMs = vo.getDurationMs();
        double throughput = vo.getThroughputPerSecond();

        // 用「带限速 + 开启时间窗」的配置触发全部派生分支
        fill(vo, task(1000L, 2048L, true, "22:00", "06:00"));

        // 派生确实发生了（证明方法真的执行了，而非空跑）
        assertEquals(1000L, vo.getConfiguredRecordsPerSecond());
        assertEquals(2048L, vo.getConfiguredBytesPerSecond());
        assertTrue(vo.isOffPeakWindowEnabled());
        assertEquals(12345L, vo.getEstimatedMinDurationMs());
        assertNotNull(vo.getCostSummary());
        assertTrue(vo.getCostSummary().contains("已配置限速"));

        // Wave10 Q1 口径逐字段完全不变
        assertEquals(totalRows, vo.getTotalRows());
        assertEquals(successRows, vo.getSuccessRows());
        assertEquals(failRows, vo.getFailRows());
        assertEquals(metaTotal, vo.getMetaTotal());
        assertEquals(rowsConsistent, vo.isRowsConsistent());
        assertEquals(durationMs, vo.getDurationMs());
        assertEquals(throughput, vo.getThroughputPerSecond(), DELTA);
    }

    // ==================== ⑨ 配置回显 / 负值归一 / 摘要文案 ====================

    @Test
    public void configEchoNegativeNormalizedAndCostSummary() {
        // 负值配置归一 0，且摘要不含与 Wave10 冲突的文案
        DatabaseSyncPerfAdviceVO vo = buildVo(5000L, 5000L, 3200L, 1562.5D);
        fill(vo, task(-5L, -10L, false, "", ""));

        assertEquals(0L, vo.getConfiguredRecordsPerSecond());
        assertEquals(0L, vo.getConfiguredBytesPerSecond());
        assertEquals(0L, vo.getEstimatedMinDurationMs());
        assertNotNull(vo.getCostSummary());
        assertTrue(vo.getCostSummary().contains("未配置限速"));
        assertTrue(vo.getCostSummary().contains("5000 行"));
        assertFalse(vo.getCostSummary().contains("数据总量为 0，一致性良好"));

        // 字节限速回显
        DatabaseSyncPerfAdviceVO withBytes = buildVo(5000L, 5000L, 3200L, 1562.5D);
        fill(withBytes, task(0L, 2048L, false, "", ""));
        assertEquals(2048L, withBytes.getConfiguredBytesPerSecond());
        assertTrue(withBytes.getCostSummary().contains("已配置限速"));
    }

    // ==================== ⑩ 未迁移任务（migrated=false）安全 0 值 ====================

    @Test
    public void unmigratedTask_safeZeroValuesAndConfigEcho() {
        DatabaseSyncPerfAdviceVO vo = buildVo(0L, 0L, 0L, 0D);
        vo.setMigrated(false);
        fill(vo, task(1000L, 2048L, true, "22:00", "06:00"));

        // 配置照常回显
        assertEquals(1000L, vo.getConfiguredRecordsPerSecond());
        assertEquals(2048L, vo.getConfiguredBytesPerSecond());
        assertTrue(vo.isOffPeakWindowEnabled());
        assertEquals("22:00 ~ 06:00", vo.getOffPeakWindowText());
        // 全 0 输入下派生量安全为 0，不 NPE
        assertEquals(0.0D, vo.getAvgMsPerThousandRows(), DELTA);
        assertEquals(0L, vo.getEstimatedMinDurationMs());
        assertNotNull(vo.getCostSummary());
        assertFalse(vo.getCostSummary().contains("数据总量为 0，一致性良好"));
    }

    // ==================== 工具：构造 VO / 反射造 task / 反射调用私有方法 ====================

    /** 构造只含 Wave10 既有口径字段的 VO（其余字段保持默认） */
    private static DatabaseSyncPerfAdviceVO buildVo(long totalRows, long successRows,
                                                    long durationMs, double throughput) {
        DatabaseSyncPerfAdviceVO vo = new DatabaseSyncPerfAdviceVO();
        vo.setTotalRows(totalRows);
        vo.setSuccessRows(successRows);
        vo.setDurationMs(durationMs);
        vo.setThroughputPerSecond(throughput);
        return vo;
    }

    /**
     * 反射构造 DatabaseSyncTask 桩：只设置 Wave11 契约的 5 个新字段
     * （recordsPerSecond / bytesPerSecond / enableOffPeakWindow / windowStart / windowEnd），
     * 不依赖 setter 是否存在，也不触碰其它字段。
     */
    private static DatabaseSyncTask task(long records, long bytes, boolean windowEnabled,
                                         String start, String end) {
        DatabaseSyncTask t = new DatabaseSyncTask();
        setField(t, "recordsPerSecond", records);
        setField(t, "bytesPerSecond", bytes);
        setField(t, "enableOffPeakWindow", windowEnabled);
        setField(t, "windowStart", start);
        setField(t, "windowEnd", end);
        return t;
    }

    /** 反射调用私有 fillCostMetrics(DatabaseSyncPerfAdviceVO, DatabaseSyncTask) */
    private static void fill(DatabaseSyncPerfAdviceVO vo, DatabaseSyncTask task) {
        try {
            Method m = DatabaseSyncPerfAdviceServiceImpl.class.getDeclaredMethod(
                    "fillCostMetrics", DatabaseSyncPerfAdviceVO.class, DatabaseSyncTask.class);
            m.setAccessible(true);
            m.invoke(new DatabaseSyncPerfAdviceServiceImpl(), vo, task);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        } catch (Exception e) {
            throw new IllegalStateException("反射调用 fillCostMetrics 失败", e);
        }
    }

    /** 沿类继承链查找并设置字段（按字段名，兼容无 setter 的契约实现） */
    private static void setField(Object target, String name, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("设置字段失败: " + name, e);
            }
        }
        throw new IllegalStateException("未找到字段: " + name);
    }
}
