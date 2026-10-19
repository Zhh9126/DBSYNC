/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
        AtomicLong total = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(1);

        if (config.isAsync()) {
            // 异步模式：writer 独立线程消费 channel
            ExecutorService writerPool = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "engine-writer");
                t.setDaemon(true);
                return t;
            });
            writerPool.submit(() -> {
                try {
                    long w = writer.write(channel);
                    total.addAndGet(w);
                } catch (Exception e) {
                    logger.error("async writer error", e);
                } finally {
                    latch.countDown();
                }
            });
            reader.read(channel);
            channel.close();
            latch.await(1, TimeUnit.HOURS);
            writerPool.shutdownNow();
        } else {
            // 同步模式：reader 与 writer 同线程协作（reader 推满一批后 writer 消费）
            long r = reader.read(channel);
            channel.close();
            long w = writer.write(channel);
            total.addAndGet(r >= 0 ? r : w);
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
