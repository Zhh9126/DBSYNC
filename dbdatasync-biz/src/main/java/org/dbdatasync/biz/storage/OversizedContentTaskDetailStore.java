package org.dbdatasync.biz.storage;

import org.dbdatasync.sdk.storage.TaskDetailContentStore;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * task_detail 超大内容外置的 biz 桥接实现：把 {@link TaskDetailContentStore} SPI
 * 挂到 {@link OversizedContentService} 上（分块外置 + 引用串回填），
 * 使校验/迁移明细的 DATA 列在超过 65535 字节时自动完整外置，取代截断。
 *
 * <p>接线方式：本组件是 Spring 单例（web 扫 {@code org.dbdatasync} 全包），
 * 初始化时安装为 {@link TaskDetailContentStore#current()}，销毁时卸载。
 * 无本组件（如仅 parser 运行）时 {@code TaskDetailUtil.serializeContent}
 * 保持既有截断行为，零变化。</p>
 *
 * <p>读取端 {@link TaskDetailUtil#deserializeContent} 识别 {@code DBDS-REF:}
 * 引用串后经 {@link #resolve} 反解为完整 JSON 再解析，明细展示不丢内容。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Component
public class OversizedContentTaskDetailStore implements TaskDetailContentStore {

    private final OversizedContentService oversizedContentService;

    /**
     * 构造桥接实现。
     *
     * @param oversizedContentService 超大内容外置服务（不可为 null）
     */
    public OversizedContentTaskDetailStore(OversizedContentService oversizedContentService) {
        if (oversizedContentService == null) {
            throw new IllegalArgumentException("oversizedContentService must not be null");
        }
        this.oversizedContentService = oversizedContentService;
    }

    @PostConstruct
    public void init() {
        TaskDetailContentStore.install(this);
    }

    @PreDestroy
    public void destroy() {
        TaskDetailContentStore.uninstall();
    }

    @Override
    public String store(String content) {
        return oversizedContentService.store(content);
    }

    @Override
    public String resolve(String stored) {
        return oversizedContentService.resolve(stored);
    }
}
