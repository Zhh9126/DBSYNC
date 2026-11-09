/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.runtime;

import org.dbdatasync.biz.slowsql.SlowSqlService;
import org.dbdatasync.biz.storage.OversizedContentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * Wave23：收口 Wave17/20 的「重启即丢失」半成品。
 *
 * <p>两个文件实现（{@code FileChunkStore} / {@code PersistentSlowSqlStore}）此前已存在，
 * 但默认装配仍是内存实现，需要手工调用才生效。这里在容器启动时按配置自动切换，
 * 让超大内容外置与慢 SQL 采集默认可跨重启恢复。</p>
 */
@Component
public class RuntimePersistenceConfig {

    private static final Logger logger = LoggerFactory.getLogger(RuntimePersistenceConfig.class);

    @Resource
    private OversizedContentService oversizedContentService;

    @Resource
    private SlowSqlService slowSqlService;

    @Value("${dbdatasync.runtime.persist:true}")
    private boolean persist;

    @Value("${dbdatasync.runtime.persistDir:data/runtime}")
    private String persistDir;

    @PostConstruct
    public void init() {
        if (!persist) {
            logger.info("runtime persistence disabled (memory mode)");
            return;
        }
        String dir = persistDir == null || persistDir.trim().isEmpty() ? "data/runtime" : persistDir.trim();
        try {
            oversizedContentService.useFileStore(dir + "/chunks");
            slowSqlService.enablePersistence(dir);
            logger.info("runtime persistence enabled: dir={}, chunks={}, slowSql={}",
                    dir, oversizedContentService.storeType(), slowSqlService.persistenceType());
        } catch (Exception e) {
            // 目录不可写时保留内存实现，不影响主流程
            logger.warn("runtime persistence unavailable, fallback to memory: {}", e.getMessage());
        }
    }
}
