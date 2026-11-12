package org.dbdatasync.biz.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * 基于文件系统的 {@link ChunkStore} 持久化实现。
 * <p>
 * 目录布局：&lt;rootDir&gt;/&lt;sanitizedRefId&gt;/&lt;index&gt;.chunk，
 * 每个分块为一个独立 UTF-8 文本文件。refId 会做安全净化（仅保留字母、数字、
 * 下划线、连字符与点，其余字符替换为下划线），防止路径穿越与非法文件名。
 * 相比 {@link InMemoryChunkStore}，本实现可在进程重启后恢复分块数据，
 * 适用于生产环境的超大内容外置落存。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class FileChunkStore implements ChunkStore {

    /** 默认字符集：UTF-8。 */
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** 存储根目录。 */
    private final File rootDir;

    /**
     * 构造文件分块存储。
     *
     * @param rootDirPath 存储根目录路径，目录不存在时在写入时自动创建
     */
    public FileChunkStore(String rootDirPath) {
        if (rootDirPath == null || rootDirPath.trim().length() == 0) {
            throw new IllegalArgumentException("rootDirPath must not be empty");
        }
        this.rootDir = new File(rootDirPath);
    }

    /**
     * 以既有 File 对象构造（便于测试使用临时目录）。
     *
     * @param rootDir 存储根目录
     */
    public FileChunkStore(File rootDir) {
        if (rootDir == null) {
            throw new IllegalArgumentException("rootDir must not be null");
        }
        this.rootDir = rootDir;
    }

    @Override
    public void save(String refId, int index, String payload) {
        validateRefId(refId);
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        // Wave32：外置块内容同时进 H2 运行态账本，payload 首行为块序号。
        if (org.dbdatasync.common.ledger.RuntimeLedger.isReady()) {
            org.dbdatasync.common.ledger.RuntimeLedger.append(
                    org.dbdatasync.common.ledger.RuntimeLedger.KIND_CONTENT_CHUNK,
                    refId, index + "\n" + payload);
        }
        File dir = refDir(refId);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("cannot create chunk dir: " + dir.getAbsolutePath());
        }
        File target = new File(dir, index + ".chunk");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(target), UTF_8);
            writer.write(payload);
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("save chunk failed: " + target.getAbsolutePath(), e);
        } finally {
            closeQuietly(writer);
        }
    }

    @Override
    public String load(String refId, int index) {
        validateRefId(refId);
        File target = new File(refDir(refId), index + ".chunk");
        if (!target.isFile()) {
            return null;
        }
        Reader reader = null;
        try {
            StringBuilder sb = new StringBuilder();
            reader = new InputStreamReader(new FileInputStream(target), UTF_8);
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("load chunk failed: " + target.getAbsolutePath(), e);
        } finally {
            closeQuietly(reader);
        }
    }

    @Override
    public void delete(String refId) {
        validateRefId(refId);
        File dir = refDir(refId);
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        // 先按文件名序号排序，逐个删除文件；全部成功后再尝试删除目录
        Arrays.sort(files);
        for (File f : files) {
            if (f.isFile() && !f.delete()) {
                throw new IllegalStateException("delete chunk failed: " + f.getAbsolutePath());
            }
        }
        dir.delete();
    }

    @Override
    public boolean exists(String refId) {
        validateRefId(refId);
        File dir = refDir(refId);
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".chunk")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回指定引用的子目录。
     *
     * @param refId 引用标识
     * @return 子目录对象
     */
    private File refDir(String refId) {
        return new File(rootDir, sanitize(refId));
    }

    /**
     * 净化 refId：仅保留字母、数字、下划线、连字符与点，其余替换为下划线，
     * 防止路径穿越（如 ../）与非法文件名字符。
     *
     * @param refId 原始引用标识
     * @return 净化后的目录名
     */
    static String sanitize(String refId) {
        StringBuilder sb = new StringBuilder(refId.length());
        for (int i = 0; i < refId.length(); i++) {
            char c = refId.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String result = sb.toString();
        // "." 与 ".." 是特殊的相对路径分量，整体替换为安全名称，防止目录逃逸
        if (".".equals(result) || "..".equals(result)) {
            return result.replace(".", "_");
        }
        // Windows 不允许目录名以点结尾：尾部点号替换为下划线（save/load 均走同一净化，保持一致）
        int end = result.length();
        while (end > 0 && result.charAt(end - 1) == '.') {
            end--;
        }
        if (end == 0) {
            return "_";
        }
        return end == result.length() ? result : result.substring(0, end) + "_";
    }

    /**
     * 校验 refId 非空。
     *
     * @param refId 引用标识
     */
    private static void validateRefId(String refId) {
        if (refId == null || refId.length() == 0) {
            throw new IllegalArgumentException("refId must not be empty");
        }
    }

    /**
     * 静默关闭流。
     *
     * @param closeable 可关闭对象
     */
    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignore) {
                // 静默忽略关闭异常
            }
        }
    }
    /**
     * Wave31：清理最后修改时间早于给定时间戳的引用目录（过期外置内容回收）。
     *
     * <p>引用目录的最后修改时间取「目录本身与其中最新 .chunk 文件」的最大 mtime；
     * 早于 cutoff 的目录整体删除。仅遍历 rootDir 一级子目录，不递归其它内容。</p>
     *
     * @param cutoffMillis 截止时间戳（早于该值的引用被清理），通常为 now - 保留期
     * @return 实际删除的引用目录数
     */
    public int purgeRefsOlderThan(long cutoffMillis) {
        File[] refs = rootDir.listFiles(File::isDirectory);
        if (refs == null) {
            return 0;
        }
        int purged = 0;
        for (File dir : refs) {
            long lastModified = dir.lastModified();
            File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".chunk"));
            if (files != null) {
                for (File f : files) {
                    lastModified = Math.max(lastModified, f.lastModified());
                }
            }
            if (lastModified < cutoffMillis) {
                try {
                    delete(dir.getName());
                    purged++;
                } catch (Exception ignore) {
                    // 单个引用清理失败不影响其余引用，留待下轮
                }
            }
        }
        return purged;
    }

    /**
     * 当前根目录下的引用目录数（用于运维观测与测试）。
     *
     * @return 引用目录数；根目录不存在时返回 0
     */
    public int refDirCount() {
        File[] refs = rootDir.listFiles(File::isDirectory);
        return refs == null ? 0 : refs.length;
    }
}