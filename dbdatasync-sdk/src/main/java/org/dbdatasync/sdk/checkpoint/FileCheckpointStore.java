package org.dbdatasync.sdk.checkpoint;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * 基于本地文件的 checkpoint 存储（零外部依赖）。
 *
 * <p>写入采用「临时文件 + 原子 rename」，避免进程崩溃留下半截记录；
 * 每个任务一个文件，文件名由 metaId 做安全化处理。多节点集群部署时把
 * {@code rootDir} 指向共享存储即可获得一致的位点视图。</p>
 */
public final class FileCheckpointStore implements CheckpointStore {

    private final File rootDir;

    public FileCheckpointStore(File rootDir) {
        if (rootDir == null) {
            throw new IllegalArgumentException("checkpoint rootDir is null");
        }
        this.rootDir = rootDir;
        if (!rootDir.exists() && !rootDir.mkdirs() && !rootDir.isDirectory()) {
            throw new IllegalStateException("cannot create checkpoint dir: " + rootDir);
        }
    }

    @Override
    public synchronized void save(CheckpointRecord record) {
        if (record == null || record.getMetaId() == null) {
            return;
        }
        File target = fileOf(record.getMetaId());
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        Properties p = new Properties();
        put(p, "metaId", record.getMetaId());
        put(p, "fileName", record.getFileName());
        put(p, "position", record.getPosition());
        p.setProperty("seq", String.valueOf(record.getSeq()));
        p.setProperty("sourceEventTime", String.valueOf(record.getSourceEventTime()));
        put(p, "eventTimeSource", record.getEventTimeSource());
        put(p, "idempotencyKey", record.getIdempotencyKey());
        p.setProperty("committedAt", String.valueOf(
                record.getCommittedAt() > 0 ? record.getCommittedAt() : System.currentTimeMillis()));

        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp, false);
            p.store(out, "DBDataSync transactional checkpoint");
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("write checkpoint failed: " + tmp, e);
        } finally {
            closeQuietly(out);
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                throw new IllegalStateException("commit checkpoint failed: " + target, inner);
            }
        } catch (IOException e) {
            throw new IllegalStateException("commit checkpoint failed: " + target, e);
        }
    }

    @Override
    public synchronized CheckpointRecord load(String metaId) {
        File f = fileOf(metaId);
        if (!f.isFile()) {
            return null;
        }
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
        CheckpointRecord r = new CheckpointRecord();
        r.setMetaId(p.getProperty("metaId", metaId));
        r.setFileName(p.getProperty("fileName"));
        r.setPosition(p.getProperty("position"));
        r.setSeq(parseLong(p.getProperty("seq"), 0L));
        r.setSourceEventTime(parseLong(p.getProperty("sourceEventTime"), 0L));
        r.setEventTimeSource(p.getProperty("eventTimeSource"));
        r.setIdempotencyKey(p.getProperty("idempotencyKey"));
        r.setCommittedAt(parseLong(p.getProperty("committedAt"), 0L));
        return r;
    }

    @Override
    public synchronized void remove(String metaId) {
        File f = fileOf(metaId);
        if (f.isFile()) {
            // 忽略失败：删除是尽力而为，下轮 save 会覆盖
            f.delete();
        }
    }

    public File getRootDir() {
        return rootDir;
    }

    private File fileOf(String metaId) {
        String safe = metaId.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.length() > 120) {
            safe = safe.substring(0, 120);
        }
        return new File(rootDir, "checkpoint-" + safe + ".properties");
    }

    private static void put(Properties p, String key, String value) {
        if (value != null) {
            p.setProperty(key, value);
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
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
