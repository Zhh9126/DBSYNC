/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.repush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * 跨明细聚合批量重推模块纯逻辑测试：聚合、分批、执行编排。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class RepushModuleTest {

    // ------------------------------------------------------------------
    // 测试用模拟执行器：按批次序号返回预设行数，或抛出预设异常
    // ------------------------------------------------------------------

    /**
     * 模拟执行器：记录被调用过的批次序号；按配置决定成功行数/失败。
     */
    private static class MockExecutor implements RepushExecutor {

        /** 批次序号 -> 本次应抛出的异常（非 null 则抛异常表示失败） */
        private final Map<Integer, Exception> throwByIndex = new HashMap<Integer, Exception>();

        /** 批次序号 -> 本次应返回的行数（负数表示返回值失败） */
        private final Map<Integer, Integer> rowsByIndex = new HashMap<Integer, Integer>();

        /** 实际被调用过的批次序号，按调用顺序记录 */
        private final List<Integer> invokedIndexes = new ArrayList<Integer>();

        @Override
        public int push(RepushBatch batch) throws Exception {
            invokedIndexes.add(batch.getIndex());
            if (throwByIndex.containsKey(batch.getIndex())) {
                throw throwByIndex.get(batch.getIndex());
            }
            Integer rows = rowsByIndex.get(batch.getIndex());
            return rows == null ? batch.getEstimatedRows() : rows.intValue();
        }
    }

    private static ReconcileDetail detail(String businessKey, String pk, String diffType, String payload) {
        return new ReconcileDetail(businessKey, "t_order", pk, diffType, payload);
    }

    // ------------------------------------------------------------------
    // DetailAggregator 测试
    // ------------------------------------------------------------------

    @Test
    public void testAggregateKeepsFirstOccurrenceOrder() {
        List<ReconcileDetail> details = new ArrayList<ReconcileDetail>();
        details.add(detail("B", "b1", "missing", "p-b1"));
        details.add(detail("A", "a1", "mismatch", "p-a1"));
        details.add(detail("B", "b2", "extra", "p-b2"));
        details.add(detail("C", "c1", "missing", "p-c1"));
        details.add(detail("A", "a2", "missing", "p-a2"));

        List<AggregatedRepushUnit> units = new DetailAggregator().aggregate(details);

        assertEquals(3, units.size());
        assertEquals("B", units.get(0).getBusinessKey());
        assertEquals("A", units.get(1).getBusinessKey());
        assertEquals("C", units.get(2).getBusinessKey());
        // 组内明细保序
        assertEquals(2, units.get(0).getDetails().size());
        assertEquals("b1", units.get(0).getDetails().get(0).getPkValue());
        assertEquals("b2", units.get(0).getDetails().get(1).getPkValue());
        // 合并 payload
        assertEquals("p-b1\np-b2", units.get(0).getMergedPayload());
        assertEquals("p-a1\np-a2", units.get(1).getMergedPayload());
    }

    @Test
    public void testAggregateMergedPayloadVariants() {
        DetailAggregator aggregator = new DetailAggregator();

        // 空输入
        assertTrue(aggregator.aggregate(null).isEmpty());
        assertTrue(aggregator.aggregate(new ArrayList<ReconcileDetail>()).isEmpty());

        // 单明细：mergedPayload 即自身 payload
        List<ReconcileDetail> single = new ArrayList<ReconcileDetail>();
        single.add(detail("K1", "k1", "missing", "only-payload"));
        List<AggregatedRepushUnit> singleUnits = aggregator.aggregate(single);
        assertEquals(1, singleUnits.size());
        assertEquals("only-payload", singleUnits.get(0).getMergedPayload());
        assertSame(single.get(0), singleUnits.get(0).getDetails().get(0));

        // 多明细：payload 按 "\n" 顺序拼接
        List<ReconcileDetail> multi = new ArrayList<ReconcileDetail>();
        multi.add(detail("K2", "m1", "missing", "row-1"));
        multi.add(detail("K2", "m2", "mismatch", "row-2"));
        multi.add(detail("K2", "m3", "extra", "row-3"));
        List<AggregatedRepushUnit> multiUnits = aggregator.aggregate(multi);
        assertEquals(1, multiUnits.size());
        assertEquals("row-1\nrow-2\nrow-3", multiUnits.get(0).getMergedPayload());
    }

    @Test
    public void testAggregateEmptyBusinessKeyNotMerged() {
        List<ReconcileDetail> details = new ArrayList<ReconcileDetail>();
        details.add(detail(null, "n1", "missing", "p-null-1"));
        details.add(detail(null, "n2", "extra", "p-null-2"));
        details.add(detail("", "e1", "mismatch", "p-empty-1"));
        details.add(detail("", "e2", "missing", "p-empty-2"));

        List<AggregatedRepushUnit> units = new DetailAggregator().aggregate(details);

        // null/空串各明细独立成单元，互不合并
        assertEquals(4, units.size());
        assertNull(units.get(0).getBusinessKey());
        assertEquals("p-null-1", units.get(0).getMergedPayload());
        assertNull(units.get(1).getBusinessKey());
        assertEquals("p-null-2", units.get(1).getMergedPayload());
        assertEquals("", units.get(2).getBusinessKey());
        assertEquals("p-empty-1", units.get(2).getMergedPayload());
        assertEquals("", units.get(3).getBusinessKey());
        assertEquals("p-empty-2", units.get(3).getMergedPayload());
        assertEquals(1, units.get(0).getDetails().size());
        assertEquals(1, units.get(3).getDetails().size());
    }

    // ------------------------------------------------------------------
    // BatchRepushPlanner 测试
    // ------------------------------------------------------------------

    /** 构造 n 个单元，每个单元含 rowsPerUnit 条明细 */
    private static List<AggregatedRepushUnit> makeUnits(int n, int rowsPerUnit) {
        List<AggregatedRepushUnit> units = new ArrayList<AggregatedRepushUnit>();
        for (int i = 1; i <= n; i++) {
            List<ReconcileDetail> details = new ArrayList<ReconcileDetail>();
            for (int j = 0; j < rowsPerUnit; j++) {
                details.add(detail("BK-" + i, "pk-" + i + "-" + j, "missing", "p-" + i + "-" + j));
            }
            units.add(new AggregatedRepushUnit("BK-" + i, details, "merged-" + i));
        }
        return units;
    }

    @Test
    public void testPlanExactDivision() {
        List<RepushBatch> batches = new BatchRepushPlanner().plan(makeUnits(6, 2), 3);

        assertEquals(2, batches.size());
        assertEquals(1, batches.get(0).getIndex());
        assertEquals(2, batches.get(1).getIndex());
        assertEquals(3, batches.get(0).getUnits().size());
        assertEquals(3, batches.get(1).getUnits().size());
        // estimatedRows = 批内明细总数
        assertEquals(6, batches.get(0).getEstimatedRows());
        assertEquals(6, batches.get(1).getEstimatedRows());
        // 单元顺序保持
        assertEquals("BK-1", batches.get(0).getUnits().get(0).getBusinessKey());
        assertEquals("BK-4", batches.get(1).getUnits().get(0).getBusinessKey());
    }

    @Test
    public void testPlanWithRemainder() {
        List<RepushBatch> batches = new BatchRepushPlanner().plan(makeUnits(7, 3), 4);

        assertEquals(2, batches.size());
        assertEquals(4, batches.get(0).getUnits().size());
        assertEquals(3, batches.get(1).getUnits().size());
        assertEquals(12, batches.get(0).getEstimatedRows());
        assertEquals(9, batches.get(1).getEstimatedRows());
        assertEquals(1, batches.get(0).getIndex());
        assertEquals(2, batches.get(1).getIndex());
    }

    @Test
    public void testPlanEmptyInput() {
        BatchRepushPlanner planner = new BatchRepushPlanner();
        assertTrue(planner.plan(null, 5).isEmpty());
        assertTrue(planner.plan(new ArrayList<AggregatedRepushUnit>(), 5).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPlanInvalidBatchSizeThrows() {
        new BatchRepushPlanner().plan(makeUnits(3, 1), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPlanNegativeBatchSizeThrows() {
        new BatchRepushPlanner().plan(makeUnits(3, 1), -2);
    }

    // ------------------------------------------------------------------
    // BatchRepushRunner 测试
    // ------------------------------------------------------------------

    @Test
    public void testRunnerAllSuccess() {
        List<RepushBatch> batches = new BatchRepushPlanner().plan(makeUnits(4, 3), 2);
        MockExecutor executor = new MockExecutor();

        RepushRunSummary summary = new BatchRepushRunner().run(batches, executor, true);

        assertFalse(summary.isAborted());
        assertEquals(2, summary.getTotalBatches());
        assertEquals(2, summary.getExecutedBatches());
        assertEquals(2, summary.getSuccessBatches());
        assertEquals(0, summary.getFailedBatches());
        assertTrue(summary.getFailedBatchIndexes().isEmpty());
        // 4 单元×3 明细=12 条，2 批（各 2 单元）→ 每批 estimatedRows=6，共 12
        assertEquals(12, summary.getPushedRows());
        // 全部批次都被执行
        assertEquals(2, executor.invokedIndexes.size());
        assertTrue(executor.invokedIndexes.contains(1));
        assertTrue(executor.invokedIndexes.contains(2));
    }

    @Test
    public void testRunnerFailureSummaryWithoutAbort() {
        List<RepushBatch> batches = new BatchRepushPlanner().plan(makeUnits(5, 2), 2);
        MockExecutor executor = new MockExecutor();
        // 批次 1：返回负数（返回值失败）；批次 2：抛异常；其余成功
        executor.rowsByIndex.put(1, -1);
        executor.throwByIndex.put(2, new RuntimeException("target write failed"));

        RepushRunSummary summary = new BatchRepushRunner().run(batches, executor, false);

        assertFalse(summary.isAborted());
        assertEquals(3, summary.getTotalBatches());
        assertEquals(3, summary.getExecutedBatches());
        assertEquals(1, summary.getSuccessBatches());
        assertEquals(2, summary.getFailedBatches());
        // failedBatchIndexes 记录失败批次序号
        assertEquals(2, summary.getFailedBatchIndexes().size());
        assertEquals(Integer.valueOf(1), summary.getFailedBatchIndexes().get(0));
        assertEquals(Integer.valueOf(2), summary.getFailedBatchIndexes().get(1));
        // 失败批的行数不计入 pushedRows，仅成功末批（1 单元×2 明细=2 条）计入
        assertEquals(2, summary.getPushedRows());
        assertEquals(3, executor.invokedIndexes.size());
    }

    @Test
    public void testRunnerAbortOnFailureStops() {
        List<RepushBatch> batches = new BatchRepushPlanner().plan(makeUnits(6, 1), 2);
        MockExecutor executor = new MockExecutor();
        // 批次 2 抛异常，abortOnFailure=true 时批次 3 不应执行
        executor.throwByIndex.put(2, new IllegalStateException("connection lost"));

        RepushRunSummary summary = new BatchRepushRunner().run(batches, executor, true);

        assertTrue(summary.isAborted());
        assertEquals(3, summary.getTotalBatches());
        // 只执行了批次 1、2，批次 3 未执行
        assertEquals(2, summary.getExecutedBatches());
        assertEquals(1, summary.getSuccessBatches());
        assertEquals(1, summary.getFailedBatches());
        assertEquals(1, summary.getFailedBatchIndexes().size());
        assertEquals(Integer.valueOf(2), summary.getFailedBatchIndexes().get(0));
        assertEquals(2, summary.getPushedRows());
        assertEquals(2, executor.invokedIndexes.size());
        assertTrue(executor.invokedIndexes.contains(1));
        assertTrue(executor.invokedIndexes.contains(2));
        assertFalse(executor.invokedIndexes.contains(3));
    }

    @Test
    public void testRunnerEmptyInput() {
        RepushRunSummary summary = new BatchRepushRunner().run(null, new MockExecutor(), true);
        assertEquals(0, summary.getTotalBatches());
        assertEquals(0, summary.getExecutedBatches());
        assertEquals(0, summary.getSuccessBatches());
        assertEquals(0, summary.getFailedBatches());
        assertTrue(summary.getFailedBatchIndexes().isEmpty());
        assertEquals(0, summary.getPushedRows());
        assertFalse(summary.isAborted());
    }
}
