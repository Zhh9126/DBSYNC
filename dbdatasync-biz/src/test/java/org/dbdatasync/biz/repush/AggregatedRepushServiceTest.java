package org.dbdatasync.biz.repush;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link AggregatedRepushService} 运行时接线层单测（纯逻辑，无 Spring 上下文）。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class AggregatedRepushServiceTest {

    /** 记录回调调用的模拟通道（模拟 ValidateSyncService.reviseDiffItems） */
    private static final class RecordingCallback implements AggregatedRepushService.DetailRepushCallback {
        final Map<String, Integer> calls = new LinkedHashMap<String, Integer>();
        final List<String> failKeys = new ArrayList<String>();

        @Override
        public int repush(String taskId, String detailId, List<Integer> itemIndexes) throws Exception {
            String key = taskId + "|" + detailId;
            if (failKeys.contains(key)) {
                throw new IllegalStateException("目标写入失败：" + key);
            }
            int prev = calls.containsKey(key) ? calls.get(key) : 0;
            calls.put(key, prev + itemIndexes.size());
            return itemIndexes.size();
        }
    }

    private static AggregatedRepushService.ItemInput item(String taskId, String detailId,
                                                          int itemIndex, String businessKey) {
        return new AggregatedRepushService.ItemInput(taskId, detailId, itemIndex, businessKey);
    }

    @Test
    public void aggregatesByBusinessKeyAndCallsOncePerDetail() {
        AggregatedRepushService service = new AggregatedRepushService();
        List<AggregatedRepushService.ItemInput> items = Arrays.asList(
                item("t1", "d1", 0, "BK-A"),
                item("t1", "d1", 1, "BK-A"),
                item("t1", "d2", 0, "BK-A"),
                item("t1", "d2", 1, "BK-B"),
                item("t2", "d3", 5, null));

        RecordingCallback callback = new RecordingCallback();
        Map<String, Integer> perItem = new LinkedHashMap<String, Integer>();
        RepushRunSummary summary = service.runAggregated(items, 2, callback, true, perItem);

        // BK-A: d1(2) + d2(1)；BK-B: d2(1)；空键: t2|d3 独立 → 4 单元，maxBatchSize=2 → 2 批
        assertEquals(2, summary.getTotalBatches());
        assertEquals(2, summary.getSuccessBatches());
        assertEquals(0, summary.getFailedBatches());
        assertFalse(summary.isAborted());
        // 每个明细只回调一次且序号齐全
        assertEquals(3, callback.calls.size());
        assertEquals(Integer.valueOf(2), callback.calls.get("t1|d1"));
        assertEquals(Integer.valueOf(2), callback.calls.get("t1|d2"));
        assertEquals(Integer.valueOf(1), callback.calls.get("t2|d3"));
        // 总成功条数 = 2+2+1 = 5
        assertEquals(5, summary.getPushedRows());
    }

    @Test
    public void abortOnFailureStopsAndRecordsFailure() {
        AggregatedRepushService service = new AggregatedRepushService();
        List<AggregatedRepushService.ItemInput> items = Arrays.asList(
                item("t1", "d1", 0, "BK-A"),
                item("t1", "d2", 0, "BK-B"),
                item("t1", "d3", 0, "BK-C"));

        RecordingCallback callback = new RecordingCallback();
        callback.failKeys.add("t1|d2");
        RepushRunSummary summary = service.runAggregated(items, 1, callback, true, null);

        // 3 单元 3 批；第 2 批失败即中止
        assertEquals(3, summary.getTotalBatches());
        assertEquals(2, summary.getExecutedBatches());
        assertEquals(1, summary.getSuccessBatches());
        assertEquals(1, summary.getFailedBatches());
        assertTrue(summary.isAborted());
        assertEquals(1, summary.getFailedBatchIndexes().size());
        assertEquals(Integer.valueOf(2), summary.getFailedBatchIndexes().get(0));
        assertEquals(1, summary.getPushedRows());
        // d3 未被回调
        assertFalse(callback.calls.containsKey("t1|d3"));
    }

    @Test
    public void emptyAndInvalidInputs() {
        AggregatedRepushService service = new AggregatedRepushService();
        RecordingCallback callback = new RecordingCallback();

        // 空输入
        RepushRunSummary empty = service.runAggregated(null, 5, callback, true, null);
        assertEquals(0, empty.getTotalBatches());

        // 非法 batchSize（由 planner 抛出）
        try {
            service.runAggregated(new ArrayList<AggregatedRepushService.ItemInput>(), 0, callback, true, null);
            throw new AssertionError("应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void nullCallbackRejected() {
        try {
            new AggregatedRepushService().runAggregated(null, 5, null, true, null);
            throw new AssertionError("应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
