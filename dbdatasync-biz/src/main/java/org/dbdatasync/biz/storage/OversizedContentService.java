package org.dbdatasync.biz.storage;

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
 * 接入 task_detail 写入链路与持久化 ChunkStore（文件/表）属后续专项。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Component("oversizedContentService")
public class OversizedContentService {

    /** 默认内联阈值：与 DBDataSync_task_detail.DATA 上限一致 */
    private volatile int maxInlineBytes = OversizedContentOffloader.INLINE_THRESHOLD_DEFAULT;

    private final InMemoryChunkStore chunkStore = new InMemoryChunkStore();

    /** 调整内联阈值（null 保持当前值；&le;0 回退默认 65535） */
    public void configure(Integer maxInlineBytes) {
        if (maxInlineBytes == null) {
            return;
        }
        this.maxInlineBytes = maxInlineBytes > 0
                ? maxInlineBytes : OversizedContentOffloader.INLINE_THRESHOLD_DEFAULT;
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

    /** 当前外置块引用数（按 InMemoryChunkStore 已知键估算不可行，返回 -1 表示未统计） */
    public int refCount() {
        return -1;
    }
}
