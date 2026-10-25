/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DataX 式同步引擎（核心编排）。
 *
 * <p>设计要点：
 * <ol>
 *     <li><b>分治</b>：外部通过 {@link Splitter} 将表切分为多个区间 Task，本引擎按并发度调度。</li>
 *     <li><b>Channel 解耦</b>：每个 Task 内 reader→{@link Channel}→writer，有界队列实现背压与限速。</li>
 *     <li><b>双模式</b>：{@link SyncMode#SYNC} 同线程直连（强一致）；{@link SyncMode#ASYNC} 独立 WriterRunner 消费（高吞吐）。</li>
 *     <li><b>断点续传</b>：writer 每批提交后通过 {@link ResumeManager} 上报游标，故障重启可续传。</li>
 * </ol>
 * </p>
 *
 * <p>本类通过函数式接口 {@link Reader} / {@link Writer} 与上层（parser）解耦，
 * 既可直接用于测试，也可由 {@code ParserComponentImpl} 适配现有 reader/writer 接入。</p>
 */
public class SyncEngine {

    private static final Logger logger = LoggerFactory.getLogger(SyncEngine.class);

    /** 读取器：从源拉取记录并写入 channel，返回读取条数（-1 表示结束） */
    public interface Reader {
        long read(Channel channel) throws Exception;
    }

    /** 写入器：从 channel 消费并落库，返回写入条数 */
    public interface Writer {
        long write(Channel channel) throws Exception;
    }

    private final EngineConfig config;
    private final ResumeManager resumeManager;

    public SyncEngine(EngineConfig config) {
        this.config = config;
        this.resumeManager = new ResumeManager(config.getSplitPk() == null ? "default" : config.getSplitPk());
        if (config.getResumePoint() != null) {
            resumeManager.report(config.getResumePoint());
        } else {
            String loaded = resumeManager.load();
            if (loaded != null) {
                config.setResumePoint(loaded);
                logger.info("从断点续传: resumePoint={}", loaded);
            }
        }
    }

    /**
     * 执行单个 Task 的同步。
     *
     * @param reader reader
     * @param writer writer
     * @return 同步总条数
     */
    public long run(Reader reader, Writer writer) throws Exception {
        Channel channel = new Channel(config.getChannelCapacity(),
                config.getRecordsPerSecond(), config.getBytesPerSecond());
        final AtomicLong total = new AtomicLong(0);
        // writer 异常不再被吞没: 捕获后向调用方上抛, 避免上游误认为同步成功(静默数据丢失)
        final AtomicReference<Throwable> writerError = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        // writer 始终在独立线程消费: 同步模式下若 reader 推送量超过 channel 容量,
        // 顺序执行(reader 完成后才启动 writer)必然死锁, 必须并行生产/消费
        Thread writerThread = new Thread(() -> {
            try {
                long w = writer.write(channel);
                if (w >= 0) {
                    total.addAndGet(w);
                }
            } catch (Throwable e) {
                writerError.compareAndSet(null, e);
            } finally {
                latch.countDown();
            }
        }, "engine-writer");
        writerThread.setDaemon(true);
        writerThread.start();

        try {
            // total 仅以 writer 实际写入计数为准, 避免读写双计
            reader.read(channel);
        } finally {
            // reader 异常也必须关闭通道并回收 writer, 避免线程泄漏
            channel.close();
        }

        if (config.isAsync()) {
            // 异步模式: 受安全超时保护, 超时中断 writer 并显式报错(不得静默 shutdownNow 丢弃批写)
            if (!latch.await(config.getWriterTimeoutSeconds(), TimeUnit.SECONDS)) {
                writerThread.interrupt();
                throw new IllegalStateException("engine writer timeout after "
                        + config.getWriterTimeoutSeconds() + "s, task aborted");
            }
        } else {
            // 同步模式: 等待 writer 完成(强一致)
            latch.await();
        }

        Throwable error = writerError.get();
        if (error != null) {
            if (error instanceof Exception) {
                throw (Exception) error;
            }
            throw new RuntimeException(error);
        }
        return total.get();
    }

    public ResumeManager getResumeManager() {
        return resumeManager;
    }

    public EngineConfig getConfig() {
        return config;
    }
}
