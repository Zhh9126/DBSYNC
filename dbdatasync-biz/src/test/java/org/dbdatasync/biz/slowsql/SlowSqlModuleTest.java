package org.dbdatasync.biz.slowsql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * 慢 SQL 采集模块单元测试：环形缓冲、阈值标记、采样率、分析统计与并发冒烟。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class SlowSqlModuleTest {

    /** 构造一条采集记录 */
    private SlowSqlRecord record(String sql, String source, long executeMillis) {
        return new SlowSqlRecord(sql, source, executeMillis, 1L, System.currentTimeMillis(), false);
    }

    /** 环形缓冲：容量满后覆盖最旧记录，snapshot 按存入顺序返回且为独立副本 */
    @Test
    public void testRingBufferCapacityAndEviction() {
        SlowSqlThreshold threshold = new SlowSqlThreshold();
        threshold.setMaxRecords(3);
        RingBufferSlowSqlStore store = new RingBufferSlowSqlStore(threshold);

        for (int i = 1; i <= 5; i++) {
            store.collect(record("sql-" + i, "task-a", 10L));
        }

        List<SlowSqlRecord> snapshot = store.snapshot();
        assertEquals(3, snapshot.size());
        assertEquals("sql-3", snapshot.get(0).getSql());
        assertEquals("sql-4", snapshot.get(1).getSql());
        assertEquals("sql-5", snapshot.get(2).getSql());
        assertEquals(3, store.size());

        // snapshot 为副本：外部修改不影响内部状态
        snapshot.clear();
        assertEquals(3, store.snapshot().size());
    }

    /** 阈值标记：达到阈值标 true，未达标 false */
    @Test
    public void testThresholdMarking() {
        SlowSqlThreshold threshold = new SlowSqlThreshold();
        threshold.setThresholdMillis(1000L);
        RingBufferSlowSqlStore store = new RingBufferSlowSqlStore(threshold);

        store.collect(record("fast", "task-a", 999L));
        store.collect(record("boundary", "task-a", 1000L));
        store.collect(record("slow", "task-a", 1500L));

        List<SlowSqlRecord> snapshot = store.snapshot();
        assertEquals(3, snapshot.size());
        assertFalse(snapshot.get(0).isThresholdExceeded());
        assertTrue(snapshot.get(1).isThresholdExceeded());
        assertTrue(snapshot.get(2).isThresholdExceeded());
    }

    /** 采样率 1.0：全采样，条数一致 */
    @Test
    public void testSampleRateFull() {
        SlowSqlThreshold threshold = new SlowSqlThreshold();
        threshold.setSampleRate(1.0D);
        threshold.setMaxRecords(100);
        RingBufferSlowSqlStore store = new RingBufferSlowSqlStore(threshold);

        for (int i = 0; i < 10; i++) {
            store.collect(record("sql-" + i, "task-b", 100L * i));
        }

        assertEquals(10, store.snapshot().size());

        // shouldSample 语义：>= 1 恒为 true
        for (int i = 0; i < 100; i++) {
            assertTrue(threshold.shouldSample());
        }
    }

    /** 分析统计：avg / max / p95 / topN 与慢 SQL 计数 */
    @Test
    public void testAnalyzeAvgMaxP95TopN() {
        List<SlowSqlRecord> records = new ArrayList<SlowSqlRecord>();
        for (int i = 1; i <= 20; i++) {
            records.add(record("sql-" + i, "task-c", i * 100L));
        }

        SlowSqlReport report = SlowSqlAnalyzer.analyze(records, 1500L, 3);

        assertEquals(20, report.getTotalCount());
        // 1500/1600/.../2000 共 6 条超过阈值
        assertEquals(6, report.getSlowCount());
        // 平均 = (100+...+2000)/20 = 21000/20 = 1050
        assertEquals(1050L, report.getAvgMillis());
        assertEquals(2000L, report.getMaxMillis());
        // p95 索引 = ceil(0.95*20)-1 = 19-1 = 18 → 第 19 小 = 1900
        assertEquals(1900L, report.getP95Millis());
        // topN=3：2000 / 1900 / 1800 降序
        assertEquals(3, report.getTopSlowest().size());
        assertEquals(2000L, report.getTopSlowest().get(0).getExecuteMillis());
        assertEquals(1900L, report.getTopSlowest().get(1).getExecuteMillis());
        assertEquals(1800L, report.getTopSlowest().get(2).getExecuteMillis());
    }

    /** 空列表与 null 输入：返回全 0 报告，不抛异常 */
    @Test
    public void testAnalyzeEmptyList() {
        SlowSqlReport empty = SlowSqlAnalyzer.analyze(new ArrayList<SlowSqlRecord>(), 1000L, 5);
        assertEquals(0, empty.getTotalCount());
        assertEquals(0, empty.getSlowCount());
        assertEquals(0L, empty.getAvgMillis());
        assertEquals(0L, empty.getMaxMillis());
        assertEquals(0L, empty.getP95Millis());
        assertTrue(empty.getTopSlowest().isEmpty());

        SlowSqlReport nullSafe = SlowSqlAnalyzer.analyze(null, 1000L, 5);
        assertEquals(0, nullSafe.getTotalCount());
        assertTrue(nullSafe.getTopSlowest().isEmpty());
    }

    /** 并发冒烟：4 线程各 collect 100 条，总量不超容量且无异常 */
    @Test
    public void testConcurrentCollectSmoke() throws Exception {
        final SlowSqlThreshold threshold = new SlowSqlThreshold();
        threshold.setMaxRecords(200);
        final RingBufferSlowSqlStore store = new RingBufferSlowSqlStore(threshold);
        final int threads = 4;
        final int perThread = 100;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();
            for (int t = 0; t < threads; t++) {
                final int taskId = t;
                tasks.add(new Callable<Void>() {
                    @Override
                    public Void call() {
                        for (int i = 0; i < perThread; i++) {
                            store.collect(record("sql-" + taskId + "-" + i,
                                    "task-" + taskId, 1000L + i));
                        }
                        return null;
                    }
                });
            }
            List<Future<Void>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        // 总量 400 条被环形缓冲截断，不超过容量
        List<SlowSqlRecord> snapshot = store.snapshot();
        assertTrue(snapshot.size() <= threshold.getMaxRecords());
        assertEquals(threshold.getMaxRecords(), snapshot.size());
    }
}
