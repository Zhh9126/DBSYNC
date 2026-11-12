package org.dbdatasync.sdk.checkpoint;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已确认幂等键账本：内存去重 + 追加式文件持久化（重启后可恢复）。
 *
 * <p>与 {@link FileCheckpointStore} 同目录。账本只记录「已成功提交的键」，
 * 不记录处理中的键；崩溃时未确认的事件会重放，由账本拦截，
 * 从而把「至少一次」收敛到「同一变更最多生效一次」。</p>
 */
public final class IdempotencyLedger {

    private final File file;
    private final int maxEntries;
    private final LinkedHashMap<String, Long> committed = new LinkedHashMap<String, Long>();

    public IdempotencyLedger(File file) {
        this(file, 200000);
    }

    public IdempotencyLedger(File file, int maxEntries) {
        if (file == null) {
            throw new IllegalArgumentException("ledger file is null");
        }
        this.file = file;
        this.maxEntries = maxEntries > 0 ? maxEntries : 200000;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("cannot create ledger dir: " + parent);
        }
        load();
    }

    public synchronized boolean isDuplicate(String key) {
        return key != null && committed.containsKey(key);
    }

    /** 标记一条变更已成功提交；重复标记幂等 */
    public synchronized void mark(String key) {
        if (key == null || key.isEmpty() || committed.containsKey(key)) {
            return;
        }
        append(key);
        committed.put(key, System.currentTimeMillis());
        if (committed.size() > maxEntries) {
            compact();
        }
    }

    public synchronized int size() {
        return committed.size();
    }

    public synchronized void clear() {
        committed.clear();
        if (file.isFile()) {
            file.delete();
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String key = line.trim();
                if (!key.isEmpty()) {
                    committed.put(key, 0L);
                }
            }
        } catch (IOException ignored) {
            // 账本损坏时按空处理，退化为「至少一次」，不阻断同步
        } finally {
            closeQuietly(reader);
        }
    }

    private void append(String key) {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8));
            writer.write(key);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("append idempotency ledger failed: " + file, e);
        } finally {
            closeQuietly(writer);
        }
    }

    /** 账本超过上限时保留最近 maxEntries/2 条，避免无限增长 */
    private void compact() {
        int keep = Math.max(1, maxEntries / 2);
        List<String> keys = new ArrayList<String>(committed.keySet());
        List<String> retained = keys.subList(Math.max(0, keys.size() - keep), keys.size());
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(tmp, false), StandardCharsets.UTF_8));
            for (String k : retained) {
                writer.write(k);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            return;
        } finally {
            closeQuietly(writer);
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            committed.clear();
            for (String k : retained) {
                committed.put(k, System.currentTimeMillis());
            }
        } catch (IOException ignored) {
            // 压缩失败不影响正确性，仅占更多磁盘
        }
    }

    /** 仅供单测/运维查看 */
    public synchronized Set<Map.Entry<String, Long>> entries() {
        return new LinkedHashMap<String, Long>(committed).entrySet();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
