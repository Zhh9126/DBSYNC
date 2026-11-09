package org.dbdatasync.biz.slowsql;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 可跨重启恢复的慢 SQL 存储（Wave23 收口 Wave17 半成品）。
 *
 * <p>继承 {@link RingBufferSlowSqlStore}，保留原有采样/阈值/环形覆盖语义，
 * 只在 {@link #collect} 之后追加一行本地日志；构造时回放最近 N 条。
 * 不连库、不改表、不引入依赖。文件损坏时降级为纯内存，不影响采集主链路。</p>
 */
public class PersistentSlowSqlStore extends RingBufferSlowSqlStore {

    private final File file;
    private final int maxRecords;

    public PersistentSlowSqlStore(SlowSqlThreshold threshold, File file) {
        super(threshold);
        if (file == null) {
            throw new IllegalArgumentException("slow sql persistence file is null");
        }
        this.file = file;
        int limit = threshold == null ? SlowSqlThreshold.DEFAULT_MAX_RECORDS : threshold.getMaxRecords();
        this.maxRecords = limit > 0 ? limit : SlowSqlThreshold.DEFAULT_MAX_RECORDS;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("cannot create slow sql dir: " + parent);
        }
        replay();
    }

    @Override
    public synchronized void collect(SlowSqlRecord record) {
        if (record == null) {
            return;
        }
        int before = size();
        super.collect(record);
        // 采样被丢弃 / 环形缓冲未变化时不需要落盘
        if (size() == before && before >= maxRecords) {
            return;
        }
        append(record);
    }

    public String storeType() {
        return "file";
    }

    public File getFile() {
        return file;
    }

    private void append(SlowSqlRecord record) {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8));
            writer.write(encode(record));
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // 落盘失败不影响内存采集
        } finally {
            close(writer);
        }
    }

    private void replay() {
        if (!file.isFile()) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            return;
        } finally {
            close(reader);
        }
        int from = Math.max(0, lines.size() - maxRecords);
        for (String line : lines.subList(from, lines.size())) {
            SlowSqlRecord record = decode(line);
            if (record != null) {
                super.collect(record);
            }
        }
    }

    private static String encode(SlowSqlRecord r) {
        return String.valueOf(r.getCapturedAt()) + '\t'
                + r.getExecuteMillis() + '\t'
                + r.getRows() + '\t'
                + (r.isThresholdExceeded() ? 1 : 0) + '\t'
                + escape(r.getSource()) + '\t'
                + escape(r.getSql());
    }

    private static SlowSqlRecord decode(String line) {
        String[] parts = line.split("\t", 6);
        if (parts.length < 6) {
            return null;
        }
        try {
            return new SlowSqlRecord(parts[5], parts[4],
                    Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                    Long.parseLong(parts[0]), "1".equals(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\t", " ").replace("\r", " ").replace("\n", " ");
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
