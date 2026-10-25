package org.dbdatasync.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Wave3 并发压力与BUG测试：Channel 契约/限速死循环、SyncEngine 异步异常吞没。
 * 断言修复后的期望行为（修复前运行应失败或卡死，即为BUG复现）。
 */
public class Wave3ChannelStressTest {

    private static Record rec(String v) {
        List<Object> cols = new ArrayList<>();
        cols.add(v);
        return new Record(cols, null);
    }

    // ==================== 1. pull 契约: 非关闭态超时不得返回 null ====================

    @Test
    public void pull_slowProducer_timeoutMustNotEndStream() throws Exception {
        final Channel ch = new Channel(16, 0, 0);
        final AtomicInteger nullReturns = new AtomicInteger(0);

        Thread writer = new Thread(() -> {
            try {
                // 空队列 pull 超时后应继续等待, 而不是把 null 当流结束
                for (int i = 0; i < 3; i++) {
                    Record r = ch.pull(100);
                    if (r == null) {
                        nullReturns.incrementAndGet();
                        continue;
                    }
                    break;
                }
            } catch (InterruptedException ignored) {
            }
        });
        writer.start();

        // 生产者 500ms 后才推第一条(超过 pull 超时)
        Thread.sleep(500);
        ch.push(rec("hello"));
        writer.join(3000);
        ch.close();

        assertTrue("空队列超时被当作流结束返回null(writer早退, 契约被违反)",
                nullReturns.get() == 0);
    }

    // ==================== 2. 限速令牌桶: 单条记录大于速率上限不得死循环 ====================

    @Test
    public void push_recordLargerThanByteRate_mustTerminate() throws Exception {
        // 限速 1KB/s, 单条记录 ~2KB
        final Channel ch = new Channel(16, 0, 1024);
        final AtomicBoolean done = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 2048; i++) {
                    sb.append('x');
                }
                ch.push(rec(sb.toString()));
                done.set(true);
            } catch (InterruptedException ignored) {
            }
        });
        producer.start();
        // 5 秒内必须完成(允许按限速等待若干秒)
        for (int i = 0; i < 50 && !done.get(); i++) {
            Thread.sleep(100);
        }
        assertTrue("单条记录大于限速上限时 push 死循环(令牌桶容量=速率)", done.get());
        producer.join(3000);
        ch.close();
    }

    // ==================== 3. SyncEngine 异步模式 writer 异常必须上抛 ====================

    @Test
    public void syncEngine_asyncWriterFailure_mustPropagate() throws Exception {
        EngineConfig cfg = new EngineConfig();
        cfg.setAsync(true);
        cfg.setSplitPk("wave3-test-" + System.nanoTime());
        SyncEngine engine = new SyncEngine(cfg);

        SyncEngine.Reader reader = channel -> {
            channel.push(rec("r1"));
            channel.push(rec("r2"));
            return 2;
        };
        SyncEngine.Writer failingWriter = channel -> {
            Record r;
            long n = 0;
            while ((r = channel.pull(1000)) != null) {
                n++;
                if (n >= 1) {
                    throw new RuntimeException("模拟目标库写入失败");
                }
            }
            return n;
        };
        try {
            engine.run(reader, failingWriter);
            fail("异步模式writer异常被吞没(上游误认为同步成功→静默数据丢失)");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("模拟目标库写入失败"));
        }
    }

    // ==================== 4. 并发压力: 多生产者-单消费者 高频压 Channel ====================

    @Test
    public void channel_concurrentStress_noLossNoDuplication() throws Exception {
        final int producers = 8;
        final int perProducer = 5000;
        final Channel ch = new Channel(256, 0, 0);
        final AtomicLong consumed = new AtomicLong(0);
        final CountDownLatch doneLatch = new CountDownLatch(1);

        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    Record r = ch.pull(1000);
                    if (r == null) {
                        if (ch.isClosed() && ch.size() == 0) {
                            break;
                        }
                        continue;
                    }
                    consumed.incrementAndGet();
                }
            } catch (InterruptedException ignored) {
            } finally {
                doneLatch.countDown();
            }
        });
        consumer.start();

        List<Thread> ps = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int id = p;
            Thread t = new Thread(() -> {
                try {
                    for (int i = 0; i < perProducer; i++) {
                        ch.push(rec("p" + id + "-" + i));
                    }
                } catch (InterruptedException ignored) {
                }
            });
            ps.add(t);
            t.start();
        }
        for (Thread t : ps) {
            t.join(60000);
        }
        ch.close();
        assertTrue("消费者应在关闭后退出", doneLatch.await(30, TimeUnit.SECONDS));

        assertEquals("压力下数据丢失/重复", (long) producers * perProducer, consumed.get());
    }

    // ==================== 5. SyncEngine 正常路径并发压测 ====================

    @Test
    public void syncEngine_syncMode_stress100k() throws Exception {
        EngineConfig cfg = new EngineConfig();
        cfg.setSplitPk("wave3-stress-" + System.nanoTime());
        SyncEngine engine = new SyncEngine(cfg);

        final int total = 100_000;
        final AtomicLong written = new AtomicLong(0);
        long r = engine.run(channel -> {
            for (int i = 0; i < total; i++) {
                channel.push(rec("row-" + i));
            }
            return total;
        }, channel -> {
            Record rec;
            while ((rec = channel.pull(1000)) != null) {
                assertNotNull(rec);
                written.incrementAndGet();
            }
            return written.get();
        });

        assertEquals(total, r);
        assertEquals(total, written.get());
    }
}
