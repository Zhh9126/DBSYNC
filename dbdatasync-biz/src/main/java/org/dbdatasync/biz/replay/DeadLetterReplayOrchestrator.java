/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.replay;

import org.dbdatasync.biz.BizException;
import org.dbdatasync.biz.DataSyncService;
import org.dbdatasync.biz.ReplicationHealthService;
import org.dbdatasync.biz.SystemConfigService;
import org.dbdatasync.biz.vo.DeadLetterVO;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.parser.model.SystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 死信自动重放编排（默认关闭，系统配置页开启）。
 *
 * <p>前提：{@code DataSyncServiceImpl.sync()} 已改为「写入结果可确认」，
 * 只有明确成功才删除失败明细，因此自动重放不会丢数据。
 * 本编排只负责扫描、指数退避、次数上限与并发保护，不直接写目标库。</p>
 */
@Component
public class DeadLetterReplayOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(DeadLetterReplayOrchestrator.class);
    private static final int MAX_BATCH = 20;
    private static final long MAX_BACKOFF_MILLIS = 10 * 60 * 1000L;

    private static final class Attempt {
        final AtomicInteger count = new AtomicInteger();
        volatile long nextAllowedAt;
    }

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<String, Attempt>();

    @Resource
    private ReplicationHealthService replicationHealthService;

    @Resource
    private DataSyncService dataSyncService;

    @Resource
    private SystemConfigService systemConfigService;

    /** 每分钟一轮；开关关闭时直接返回，无任何 IO */
    @Scheduled(fixedDelay = 60_000L)
    public void replay() {
        SystemConfig config = systemConfigService.getSystemConfig();
        if (config == null || !config.isEnableDeadLetterAutoReplay()) {
            return;
        }
        int maxAttempts = clamp(config.getDeadLetterReplayMaxAttempts(), 1, 10, 3);
        long baseMillis = clamp(config.getDeadLetterReplayBaseMillis(), 50, 60_000, 500);
        Map<String, String> query = new HashMap<String, String>();
        query.put("status", "0");
        query.put("pageNum", "1");
        query.put("pageSize", String.valueOf(MAX_BATCH * 3));

        Paging<DeadLetterVO> page;
        try {
            page = replicationHealthService.queryDeadLetters(query);
        } catch (Exception e) {
            logger.warn("死信扫描失败: {}", e.getMessage());
            return;
        }
        if (page == null || page.getData() == null) {
            return;
        }
        int processed = 0;
        int succeeded = 0;
        for (DeadLetterVO item : (Collection<DeadLetterVO>) page.getData()) {
            if (processed >= MAX_BATCH) {
                break;
            }
            if (item == null || item.getId() == null || item.getMetaId() == null) {
                continue;
            }
            // 只重放带 Wave22 失败标签的记录，避免把普通明细误当死信
            if (!item.isDeadLetter() && !item.isSyncFail()) {
                continue;
            }
            String id = item.getMetaId() + "|" + item.getId();
            Attempt attempt = attempts.computeIfAbsent(id, k -> new Attempt());
            long now = System.currentTimeMillis();
            if (now < attempt.nextAllowedAt || attempt.count.get() >= maxAttempts) {
                continue;
            }
            processed++;
            Map<String, String> params = new HashMap<String, String>();
            params.put("metaId", item.getMetaId());
            params.put("messageId", item.getId());
            boolean ok;
            try {
                dataSyncService.sync(params);
                ok = true;
            } catch (Exception e) {
                ok = false;
                logger.warn("死信重放异常: id={}, error={}", id, e.getMessage());
            }
            int n = attempt.count.incrementAndGet();
            if (ok) {
                succeeded++;
                attempts.remove(id);
            } else {
                long backoff = Math.min(MAX_BACKOFF_MILLIS,
                        baseMillis * (1L << Math.min(n - 1, 20)));
                attempt.nextAllowedAt = now + backoff;
            }
        }
        if (processed > 0) {
            logger.info("死信自动重放完成: 本轮={}, 成功={}", processed, succeeded);
        }
    }

    /**
     * Wave27：单条死信手动重放（链路健康页「重放」按钮调用）。
     *
     * <p>与自动重放的差异：人工明确意图优先，不受指数退避与次数上限约束；
     * 但同样要求目标记录确实带失败标签（死信/待处理），防止把普通明细误放。
     * 重放成功或失败后都会清理自动重放的退避状态，避免自动编排与人工操作互相干扰。</p>
     *
     * @param metaId    失败明细所属元数据 id
     * @param messageId 失败明细 id
     * @return 重放结果描述（成功/失败原因）
     * @throws org.dbdatasync.BizException 记录不存在、非死信或重放执行失败
     */
    public String replaySingle(String metaId, String messageId) {
        if (metaId == null || metaId.trim().isEmpty() || messageId == null || messageId.trim().isEmpty()) {
            throw new BizException("metaId 与 messageId 不能为空");
        }
        // 先确认记录存在且确为死信/失败明细（扫最近 500 条内匹配）
        Map<String, String> query = new HashMap<String, String>();
        query.put("status", "0");
        query.put("pageNum", "1");
        query.put("pageSize", "500");
        DeadLetterVO target = null;
        try {
            Paging<DeadLetterVO> page = replicationHealthService.queryDeadLetters(query);
            if (page != null && page.getData() != null) {
                for (DeadLetterVO item : (Collection<DeadLetterVO>) page.getData()) {
                    if (item != null && metaId.equals(item.getMetaId()) && messageId.equals(item.getId())) {
                        target = item;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("死信手动重放查询失败: {}", e.getMessage());
        }
        if (target == null) {
            throw new BizException("失败明细不存在或已不在失败列表中，请刷新后重试");
        }
        if (!target.isDeadLetter() && !target.isSyncFail()) {
            throw new BizException("该记录不是死信/失败明细，不允许重放");
        }
        // 人工重放前清理退避，成功失败都清理（避免自动编排继续按旧退避压制）
        String key = metaId + "|" + messageId;
        forget(metaId, messageId);
        Map<String, String> params = new HashMap<String, String>();
        params.put("metaId", metaId);
        params.put("messageId", messageId);
        try {
            dataSyncService.sync(params);
        } catch (Exception e) {
            logger.warn("死信手动重放失败: id={}, error={}", key, e.getMessage());
            throw new BizException("重放执行失败: " + e.getMessage());
        }
        logger.info("死信手动重放成功: id={}", key);
        return "重放成功：记录已重新同步";
    }

    /** 记录被人工成功重放后清理退避状态 */
    public void forget(String metaId, String messageId) {
        attempts.remove(metaId + "|" + messageId);
    }

    public int trackedCount() {
        return attempts.size();
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }
}
