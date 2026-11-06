package org.dbdatasync.parser.util;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * MigrationRateLimiter 单元测试（标准令牌桶语义，共 10 项）。
 *
 * <p>覆盖：默认关闭零开销、fromParams 解析、各维度启用、矛盾配置「不永久挂起 + 可中断退出」、
 * 多线程共享实例、getters，以及本轮核心回归「限速真实有效（不被批次大小架空）」与
 * 「双维度取更严格者」。用例不主动 sleep 超过 3 秒，整类耗时控制在 5 秒内。</p>
 *
 * <p>配置键使用字面量（与 {@code ConfigConstant.RATE_RECORDS_PER_SECOND} /
 * {@code ConfigConstant.RATE_BYTES_PER_SECOND} 的值一致），使本测试不依赖并行工程师
 * 尚未落盘的常量，可独立编译运行。</p>
 */
public class MigrationRateLimiterTest {

    private static final String KEY_RECORDS = "rateRecordsPerSecond";
    private static final String KEY_BYTES = "rateBytesPerSecond";

    private static Map<String, String> params(String records, String bytes) {
        Map<String, String> p = new HashMap<>();
        if (records != null) {
            p.put(KEY_RECORDS, records);
        }
        if (bytes != null) {
            p.put(KEY_BYTES, bytes);
        }
        return p;
    }

    /** ① 两个 0 -> disabled 且 acquire 立即返回（计时 &lt;50ms）。 */
    @Test
    public void testBothZeroDisabledAndFast() {
        MigrationRateLimiter limiter = new MigrationRateLimiter(0, 0);
        assertFalse(limiter.isEnabled());
        assertFalse(MigrationRateLimiter.isEnabled(0, 0));
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            limiter.acquire(100, 10000L);
        }
        long costMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("disabled acquire 应立即返回, 实际耗时 " + costMs + "ms", costMs < 50);
    }

    /** ② null/空/无关 params -> disabled，且 acquire 不改变执行顺序。 */
    @Test
    public void testFromParamsDisabled() {
        assertFalse(MigrationRateLimiter.fromParams(null).isEnabled());
        assertFalse(MigrationRateLimiter.fromParams(new HashMap<String, String>()).isEnabled());
        Map<String, String> unrelated = new HashMap<>();
        unrelated.put("fullSplitKey", "id");
        unrelated.put("fullSplitCount", "8");
        MigrationRateLimiter limiter = MigrationRateLimiter.fromParams(unrelated);
        assertFalse(limiter.isEnabled());
        assertEquals(0L, limiter.getRecordsPerSecond());
        assertEquals(0L, limiter.getBytesPerSecond());
        limiter.acquire(10, 1000L);
    }

    /** ③ 非法数字/负数/空白 -> 归 0。 */
    @Test
    public void testFromParamsInvalidValuesBecomeZero() {
        MigrationRateLimiter a = MigrationRateLimiter.fromParams(params("abc", "12x"));
        assertFalse(a.isEnabled());
        assertEquals(0L, a.getRecordsPerSecond());
        assertEquals(0L, a.getBytesPerSecond());

        MigrationRateLimiter b = MigrationRateLimiter.fromParams(params("-5", "-1"));
        assertFalse(b.isEnabled());
        assertEquals(0L, b.getRecordsPerSecond());
        assertEquals(0L, b.getBytesPerSecond());

        MigrationRateLimiter c = MigrationRateLimiter.fromParams(params("  ", "0"));
        assertFalse(c.isEnabled());
        assertEquals(0L, c.getRecordsPerSecond());
        assertEquals(0L, c.getBytesPerSecond());
    }

    /** ④ records 限速启用。 */
    @Test
    public void testRecordsRateEnabled() {
        MigrationRateLimiter limiter = MigrationRateLimiter.fromParams(params("100", null));
        assertTrue(limiter.isEnabled());
        assertTrue(MigrationRateLimiter.isEnabled(100, 0));
        assertEquals(100L, limiter.getRecordsPerSecond());
        assertEquals(0L, limiter.getBytesPerSecond());
        // 初始 1 秒令牌（100）足够，首批立即通过，不触发等待
        limiter.acquire(1, 0);
    }

    /** ⑤ bytes 限速启用。 */
    @Test
    public void testBytesRateEnabled() {
        MigrationRateLimiter limiter = MigrationRateLimiter.fromParams(params(null, "1024"));
        assertTrue(limiter.isEnabled());
        assertTrue(MigrationRateLimiter.isEnabled(0, 1024));
        assertEquals(0L, limiter.getRecordsPerSecond());
        assertEquals(1024L, limiter.getBytesPerSecond());
        limiter.acquire(1, 100L);
    }

    /**
     * ⑥ 矛盾配置（1B/s + 单批 1,000,000B）在精确限速下物理上需等待约 1e6 秒：
     * 验证「不永久挂起 + 可中断退出 + 恢复中断标志 + 不抛异常」；并验证未启用维度不阻塞。
     */
    @Test
    public void testLargeRecordInterruptibleAndNonBlockingDisabledDimension() throws Exception {
        final MigrationRateLimiter limiter = new MigrationRateLimiter(0, 1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicReference<Boolean> flagAfter = new AtomicReference<>();
        final Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    limiter.acquire(1, 1_000_000L);
                    flagAfter.set(Thread.currentThread().isInterrupted());
                } catch (Throwable e) {
                    error.set(e);
                }
            }
        }, "ratelimit-large-record");
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(100);
        long t = System.nanoTime();
        worker.interrupt();
        worker.join(2000);
        long costMs = (System.nanoTime() - t) / 1_000_000L;
        assertFalse("中断后应在 2 秒内结束（不永久挂起），实际 " + costMs + "ms", worker.isAlive());
        assertNull("中断时 acquire 不应抛异常", error.get());
        assertEquals("中断标志应被恢复", Boolean.TRUE, flagAfter.get());

        // 未启用的限速维度不参与阻塞：只启用 records 时，超大 bytes 应立即放行
        MigrationRateLimiter recordsOnly = new MigrationRateLimiter(1000, 0);
        long t2 = System.nanoTime();
        recordsOnly.acquire(1, 1_000_000L);
        long cost2 = (System.nanoTime() - t2) / 1_000_000L;
        assertTrue("未启用的字节维度不应阻塞，实际 " + cost2 + "ms", cost2 < 100);
    }

    /** ⑦ 多线程并发共享实例不抛异常（4 线程各 20 次 acquire）。 */
    @Test(timeout = 3000)
    public void testConcurrentSharedInstance() throws Exception {
        // 初始令牌充足，80 次 acquire 均立即通过，不触发等待、不主动 sleep
        final MigrationRateLimiter limiter = new MigrationRateLimiter(1_000_000L, 1_000_000_000L);
        final int threads = 4;
        final int loops = 20;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < loops; i++) {
                            limiter.acquire(1, 10L);
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                }
            }, "ratelimit-test-" + t).start();
        }
        start.countDown();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNull("并发 acquire 不应抛异常", error.get());
    }

    /** ⑧ getters 正确。 */
    @Test
    public void testGetters() {
        MigrationRateLimiter limiter = new MigrationRateLimiter(123L, 456L);
        assertEquals(123L, limiter.getRecordsPerSecond());
        assertEquals(456L, limiter.getBytesPerSecond());
        assertTrue(limiter.isEnabled());
    }

    /**
     * ⑨ 核心回归：限速真实有效，长期速率不被批次大小架空。
     *
     * <p>{@code rate=200000}、每批 100000 行：初始 1 秒令牌 200000 → 前 2 批立即放行，
     * 其后每批需按 200000 行/s 补充 100000 令牌约 0.5s。连续 5 次理论总耗时约 1.5s，
     * 证明「调用次数增加 → 总耗时线性增长」；若仍是修正前的「每秒窗口清零 + 配额取 max」，
     * 5 批会在 0ms 内全部放行。同时以不限速实例做对照证据。</p>
     */
    @Test
    public void testRateLimitIsEffectiveNotBypassedByBatchSize() {
        MigrationRateLimiter limited = new MigrationRateLimiter(200_000, 0);
        long t = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limited.acquire(100_000, 0);
        }
        long costMs = (System.nanoTime() - t) / 1_000_000L;
        assertTrue("5 批共 50 万行按 20 万行/s 应约 1.5s，实际 " + costMs + "ms", costMs >= 1200);
        assertTrue("限速用例耗时应 < 4s，实际 " + costMs + "ms", costMs < 4000);

        // 不限速对照：同样 5 次调用在默认关闭下应立即返回（零开销、零行为变化）
        MigrationRateLimiter unlimited = new MigrationRateLimiter(0, 0);
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            unlimited.acquire(100_000, 0);
        }
        long unlimitedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue("不限速实例同样 5 次调用应 < 50ms，实际 " + unlimitedMs + "ms", unlimitedMs < 50);
    }

    /**
     * ⑩ 双维度独立生效、取更严格者：records 极松（100 万/s）、bytes 较严（10 万/s），
     * 单批 200000 字节仍应被字节维度限住并产生可测等待。
     */
    @Test
    public void testMostRestrictiveDimensionWins() {
        MigrationRateLimiter limiter = new MigrationRateLimiter(1_000_000L, 100_000L);
        long t = System.nanoTime();
        limiter.acquire(1, 200_000L); // 初始 10 万字节令牌，需再攒 10 万 -> 约 1 秒
        long costMs = (System.nanoTime() - t) / 1_000_000L;
        assertTrue("字节维度应独立产生等待（records 极松不足以放行），实际 " + costMs + "ms", costMs >= 500);
        limiter.acquire(1, 1); // 紧随其后仍受字节维度约束
        long totalMs = (System.nanoTime() - t) / 1_000_000L;
        assertTrue("双维度用例总耗时应 < 3s，实际 " + totalMs + "ms", totalMs < 3000);
    }
}
