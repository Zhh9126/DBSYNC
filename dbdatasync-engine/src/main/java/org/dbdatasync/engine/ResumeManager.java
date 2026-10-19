/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 断点续传管理器。
 *
 * <p>将已成功写入的游标最大值（cursor）持久化到本地文件，任务中断后重启时可从
 * {@link #load()} 读取续传点，仅同步大于该点的数据，避免重复全量重跑。</p>
 *
 * <p>这是本项目"故障可恢复同步"的关键能力：结合 {@link Splitter} 的区间切分，
 * 可在单区间失败时将游标前移，实现"至少一次"语义的高效续传。</p>
 */
public class ResumeManager {

    private final Path checkpointFile;
    private final AtomicReference<String> lastCursor = new AtomicReference<>(null);

    public ResumeManager(String taskId) {
        this.checkpointFile = Paths.get(System.getProperty("java.io.tmpdir"),
                "dbdatasync_resume_" + taskId + ".ckpt");
    }

    /**
     * 加载已有续传点。
     */
    public String load() {
        try {
            if (Files.exists(checkpointFile)) {
                try (BufferedReader r = Files.newBufferedReader(checkpointFile, StandardCharsets.UTF_8)) {
                    String line = r.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        lastCursor.set(line.trim());
                        return line.trim();
                    }
                }
            }
        } catch (IOException e) {
            // 续传点读取失败不阻断主流程，按从头开始处理
        }
        return null;
    }

    /**
     * 上报已同步游标（原子更新 + 持久化）。
     */
    public synchronized void report(Object cursor) {
        if (cursor == null) return;
        String value = cursor.toString();
        // 仅当新值"更大"（字符串/数值统一按字符串比较，数值场景建议调用方保证格式）时更新
        if (lastCursor.get() == null || value.compareTo(lastCursor.get()) > 0) {
            lastCursor.set(value);
            try (BufferedWriter w = Files.newBufferedWriter(checkpointFile, StandardCharsets.UTF_8)) {
                w.write(value);
            } catch (IOException ignored) {
                // 持久化失败不影响内存态，下次上报会重试
            }
        }
    }

    public String current() {
        return lastCursor.get();
    }

    public void clear() {
        lastCursor.set(null);
        try {
            Files.deleteIfExists(checkpointFile);
        } catch (IOException ignored) {
        }
    }
}
