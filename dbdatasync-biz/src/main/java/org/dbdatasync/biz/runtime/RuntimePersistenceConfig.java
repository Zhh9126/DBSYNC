/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.runtime;

import org.dbdatasync.biz.cutover.CutoverManagerService;
import org.dbdatasync.biz.health.ReplicationHealthTracker;
import org.dbdatasync.biz.slowsql.SlowSqlService;
import org.dbdatasync.biz.storage.OversizedContentService;
import org.dbdatasync.common.ledger.RuntimeLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Value("${dbdatasync.storage.h2.url:}")
    private String h2Url;

    @Value("${dbdatasync.storage.h2.username:sa}")
    private String h2User;

    @Value("${dbdatasync.storage.h2.password:}")
    private String h2Password;

    @Value("${dbdatasync.storage.type:h2}")
    private String storageType;

    @Value("${dbdatasync.storage.sqlite.url:}")
    private String sqliteUrl;

    @PostConstruct
    public void init() {
        // Wave32：把慢 SQL / 内容外置块 / 结构变更 / 切流编排 / 链路健康统一落到
        // 平台当前元库（SQLite 或 H2），避免「文件分散 + 进程内存重启即丢」。
        boolean sqlite = storageType != null && "sqlite".equalsIgnoreCase(storageType.trim());
        String ledgerUrl = sqlite ? sqliteUrl : h2Url;
        String ledgerUser = sqlite ? "" : h2User;
        String ledgerPassword = sqlite ? "" : h2Password;
        logger.info("runtime ledger target: type={}, url={}", storageType, ledgerUrl);
        RuntimeLedger.init(ledgerUrl, ledgerUser, ledgerPassword);
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

    // Wave32：切流编排与链路健康原本只存在进程内存，重启即清空。这里按状态变化
    // 定期把快照写入同一 H2 元库，保证运行轨迹可追溯、不随重启消失。
    @Autowired(required = false)
    private CutoverManagerService cutoverManagerService;

    @Autowired(required = false)
    private ReplicationHealthTracker replicationHealthTracker;

    private volatile String lastSignature = "";

    @Scheduled(fixedRate = 60000)
    public void snapshotRuntimeState() {
        if (!RuntimeLedger.isReady()) {
            return;
        }
        try {
            int sessions = cutoverManagerService == null ? 0 : cutoverManagerService.sessionCount();
            int links = replicationHealthTracker == null ? 0 : replicationHealthTracker.allCounters().size();
            String signature = sessions + ":" + links;
            if (signature.equals(lastSignature)) {
                return;
            }
            lastSignature = signature;
            String payload = "{\"ts\":" + System.currentTimeMillis()
                    + ",\"cutoverSessions\":" + sessions
                    + ",\"healthLinks\":" + links + "}";
            if (cutoverManagerService != null) {
                RuntimeLedger.append(RuntimeLedger.KIND_CUTOVER, "snapshot", payload);
            }
            if (replicationHealthTracker != null) {
                RuntimeLedger.append(RuntimeLedger.KIND_LINK_HEALTH, "snapshot", payload);
            }
        } catch (Exception e) {
            logger.warn("runtime ledger snapshot failed: {}", e.getMessage());
        }
    }
}
