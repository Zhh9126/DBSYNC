package org.dbdatasync.biz.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 超大内容外置服务（Wave17 运行时接线层）。
 *
 * <p>包装 {@link OversizedContentOffloader} + {@link InMemoryChunkStore}，
 * 作为 Spring 单例供 web 层（{@code ContentOffloadController}）与内容落库链路共用：
 * 内容 UTF-8 字节不超过 {@code maxInlineBytes} 时原样返回；否则分块外置并返回
 * {@code DBDS-REF:<refId>} 引用串，读取方用 {@link #resolve} 反解原文。</p>
 *
 * <p>本服务是「解除 65535 字节截断」的非破坏式路径：不改既有表结构，
 * 既有 DATA 字段在超限时改存引用串。默认块存储为进程内存实现；
 * Wave20 起可经 {@link #useFileStore(String)} 切换为文件持久化（跨重启恢复）。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Component("oversizedContentService")
public class OversizedContentService {

    private static final Logger logger = LoggerFactory.getLogger(OversizedContentService.class);


    /** 默认内联阈值：与 DBDataSync_task_detail.DATA 上限一致 */
    private volatile int maxInlineBytes = OversizedContentOffloader.INLINE_THRESHOLD_DEFAULT;

    /** 当前块存储：默认进程内存实现，可切换为文件持久化（Wave20 配置化接入） */
    private volatile ChunkStore chunkStore = new InMemoryChunkStore();

    /** 调整内联阈值（null 保持当前值；&le;0 回退默认 65535） */
    public void configure(Integer maxInlineBytes) {
        if (maxInlineBytes == null) {
            return;
        }
        this.maxInlineBytes = maxInlineBytes > 0
                ? maxInlineBytes : OversizedContentOffloader.INLINE_THRESHOLD_DEFAULT;
    }

    /**
     * 切换为进程内存存储（默认形态，重启后外置内容丢失）。
     *
     * @return 切换前的存储形态名
     */
    public String useMemoryStore() {
        String before = storeType();
        this.chunkStore = new InMemoryChunkStore();
        return before;
    }

    /**
     * 切换为文件持久化存储（{@link FileChunkStore}，进程重启后外置内容可恢复）。
     * 同一 rootDir 多次调用指向同一批文件。
     *
     * @param rootDir 文件存储根目录（不可为空）
     * @return 切换前的存储形态名
     */
    public String useFileStore(String rootDir) {
        if (rootDir == null || rootDir.trim().isEmpty()) {
            throw new IllegalArgumentException("rootDir must not be empty");
        }
        String before = storeType();
        this.chunkStore = new FileChunkStore(rootDir.trim());
        return before;
    }

    /**
     * 当前存储形态名：memory / file。
     *
     * @return 形态名
     */
    public String storeType() {
        return chunkStore instanceof FileChunkStore ? "file" : "memory";
    }

    /** 当前内联阈值 */
    public int getMaxInlineBytes() {
        return maxInlineBytes;
    }

    /**
     * 存储：内联直通或分块外置。
     *
     * @param content 原文（null 视为空串）
     * @return 原文（内联）或 {@code DBDS-REF:<refId>}（外置）
     */
    public String store(String content) {
        return OversizedContentOffloader.store(content, chunkStore, maxInlineBytes);
    }

    /**
     * 反解：REF 引用串还原原文；非引用原样返回。
     *
     * @throws IllegalStateException 块缺失或校验失败
     */
    public String resolve(String stored) {
        return OversizedContentOffloader.resolve(stored, chunkStore);
    }

    /** 删除引用串对应的全部块 */
    public void delete(String stored) {
        OversizedContentOffloader.delete(stored, chunkStore);
    }

    /** 是否为外置引用串 */
    public boolean isRef(String stored) {
        return stored != null && stored.startsWith(OversizedContentOffloader.REF_PREFIX);
    }

    /** 当前外置块引用数（ChunkStore 接口无统计能力，返回 -1 表示未统计） */
    public int refCount() {
        return -1;
    }
    /**
     * Wave31：清理过期外置内容块。
     *
     * <p>仅文件存储形态有持久块可清；内存存储随进程消亡，返回 0。
     * 保留期经系统属性 {@code dbdatasync.contentOffload.retentionDays} 配置（默认 30 天）；
     * 引用目录的最后修改时间早于保留期的整体删除，防止长期运行下外置内容目录无限膨胀。</p>
     *
     * @return 本轮删除的引用目录数；当前为内存存储时返回 0
     */
    public int purgeAgedBlocks() {
        ChunkStore store = this.chunkStore;
        if (!(store instanceof FileChunkStore)) {
            return 0;
        }
        long retentionMillis = TimeUnit.DAYS.toMillis(
                Long.getLong("dbdatasync.contentOffload.retentionDays", 30L));
        long cutoff = System.currentTimeMillis() - retentionMillis;
        int purged = ((FileChunkStore) store).purgeRefsOlderThan(cutoff);
        if (purged > 0) {
            logger.info("外置内容过期清理完成: 删除{}个引用目录, 保留期{}天", purged,
                    TimeUnit.MILLISECONDS.toDays(retentionMillis));
        }
        return purged;
    }

    /**
     * 每 6 小时执行一次过期外置内容清理（启动后延迟 10 分钟，错开启动高峰）。
     */
    @Scheduled(initialDelay = 10 * 60 * 1000L, fixedDelay = 6 * 60 * 60 * 1000L)
    public void scheduledPurgeAgedBlocks() {
        try {
            purgeAgedBlocks();
        } catch (Exception e) {
            logger.warn("外置内容过期清理异常: {}", e.getMessage());
        }
    }
}
