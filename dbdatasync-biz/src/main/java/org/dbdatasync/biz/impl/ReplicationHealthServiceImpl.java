/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.impl;

import org.dbdatasync.biz.MonitorService;
import org.dbdatasync.biz.ReplicationHealthService;
import org.dbdatasync.biz.SystemConfigService;
import org.dbdatasync.biz.health.HealthEvaluator;
import org.dbdatasync.biz.health.HealthLevel;
import org.dbdatasync.biz.health.ReplicationHealthTracker;
import org.dbdatasync.biz.model.ReplicationHealthReport;
import org.dbdatasync.biz.vo.DataVO;
import org.dbdatasync.biz.vo.DeadLetterVO;
import org.dbdatasync.biz.vo.LinkHealthVO;
import org.dbdatasync.common.enums.CommonTaskStatusEnum;
import org.dbdatasync.common.enums.TaskLevelEnum;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.SystemConfig;
import org.dbdatasync.plugin.model.ReplicationHealthContent;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.enums.ModelEnum;
import org.dbdatasync.sdk.retry.WriteFailureTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时链路健康聚合与告警。
 *
 * <p>取数：任务级 Meta（存储）+ 运行时观察点计数（内存）+ 系统配置阈值。
 * 不连源库、不改存储模型、不写位点，属于纯观测面。
 *
 * @author wuji
 * @version 1.0.0
 */
@Service
public class ReplicationHealthServiceImpl extends BaseServiceImpl implements ReplicationHealthService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /** 同一链路的告警冷却记录 */
    private final ConcurrentHashMap<String, Long> lastAlarmAt = new ConcurrentHashMap<>();

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private ReplicationHealthTracker tracker;

    @Resource
    private MonitorService monitorService;

    @Resource
    private SystemConfigService systemConfigService;

    @Override
    public ReplicationHealthReport queryHealth() {
        long now = System.currentTimeMillis();
        SystemConfig config = safeConfig();
        ReplicationHealthReport report = new ReplicationHealthReport();
        report.setGeneratedAt(now);
        report.setLagWarnSeconds(config.getLagWarnSeconds());
        report.setLagCriticalSeconds(config.getLagCriticalSeconds());
        report.setFailRateWarnPercent(config.getFailRateWarnPercent());
        report.setAlarmEnabled(config.isEnableReplicationAlarm());
        report.setPrometheusEnabled(config.isEnablePrometheus());

        final List<LinkHealthVO> links = new ArrayList<>();
        try {
            metaProfile.pageScanMetas(TaskLevelEnum.TASK.getCode(), ConfigConstant.PAGE_SIZE, page -> {
                if (page == null) {
                    return;
                }
                for (Meta meta : page) {
                    try {
                        LinkHealthVO vo = evaluateOne(meta, config, now);
                        if (vo != null) {
                            links.add(vo);
                        }
                    } catch (Exception e) {
                        logger.warn("链路健康评估跳过异常任务: {}", e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            logger.warn("链路健康扫描失败: {}", e.getMessage());
        }

        links.sort((a, b) -> {
            int byLevel = Integer.compare(HealthLevel.safeValueOf(b.getLevel()).getSeverity(),
                    HealthLevel.safeValueOf(a.getLevel()).getSeverity());
            return byLevel != 0 ? byLevel : Long.compare(b.getStallSeconds(), a.getStallSeconds());
        });

        int running = 0;
        int healthy = 0;
        int warn = 0;
        int critical = 0;
        int stale = 0;
        int idle = 0;
        int scoreSum = 0;
        long events = 0;
        long recovered = 0;
        long deadLetters = 0;
        long maxStall = 0;
        long maxLag = 0;
        for (LinkHealthVO vo : links) {
            if (vo.isRunning()) {
                running++;
                scoreSum += vo.getScore();
            }
            HealthLevel level = HealthLevel.safeValueOf(vo.getLevel());
            switch (level) {
                case HEALTHY:
                    healthy++;
                    break;
                case WARN:
                    warn++;
                    break;
                case CRITICAL:
                    critical++;
                    break;
                case STALE:
                    stale++;
                    break;
                default:
                    idle++;
                    break;
            }
            events += vo.getEventsTotal();
            recovered += vo.getRetryRecovered();
            deadLetters += vo.getDeadLetters();
            maxStall = Math.max(maxStall, vo.getStallSeconds());
            maxLag = Math.max(maxLag, vo.getLagSeconds());
        }

        report.setLinks(links);
        report.setTotalLinks(links.size());
        report.setRunningLinks(running);
        report.setHealthyLinks(healthy);
        report.setWarnLinks(warn);
        report.setCriticalLinks(critical);
        report.setStaleLinks(stale);
        report.setIdleLinks(idle);
        report.setHealthScore(running == 0 ? 100 : Math.round((float) scoreSum / running));
        report.setEventsTotal(events);
        report.setRetryRecoveredTotal(recovered);
        report.setDeadLetterTotal(deadLetters);
        report.setMaxStallSeconds(maxStall);
        report.setMaxLagSeconds(maxLag);
        return report;
    }

    private LinkHealthVO evaluateOne(Meta meta, SystemConfig config, long now) {
        if (meta == null || meta.getTaskId() == null) {
            return null;
        }
        Mapping mapping = profileComponent.getMapping(meta.getTaskId());
        if (mapping == null || !StringUtil.equals(ConfigConstant.MAPPING, mapping.getType())) {
            return null;
        }
        ReplicationHealthTracker.Counters counters = tracker.counters(meta.getId());
        boolean running = meta.getState() == CommonTaskStatusEnum.RUNNING.getCode();
        boolean hasEvent = counters.events > 0;
        long baseline = counters.lastEventTime;
        if (baseline <= 0) {
            baseline = firstPositive(meta.getBeginTime(),
                    meta.getUpdateTime() == null ? 0L : meta.getUpdateTime(),
                    counters.firstSeenTime);
        }
        long stallSeconds = baseline > 0 ? Math.max(0L, (now - baseline) / 1000L) : 0L;

        long total = value(meta.getTotal());
        long success = value(meta.getSuccess());
        long fail = value(meta.getFail());
        double failRate = total > 0 ? fail * 100d / total : 0d;

        // Wave23：源端事件时间可用时，用真实端到端复制延迟替代「距最后事件的停滞时长」
        boolean realLag = counters.lastSourceEventTime > 0
                && org.dbdatasync.sdk.model.ChangedOffset.EVENT_TIME_SOURCE.equals(counters.lagSource);
        long lagSeconds = realLag ? Math.max(0L, (now - counters.lastSourceEventTime) / 1000L) : 0L;
        HealthEvaluator.Output health = HealthEvaluator.evaluate(new HealthEvaluator.Input(
                running, hasEvent, stallSeconds, failRate, counters.deadLetters,
                config.getLagWarnSeconds(), config.getLagCriticalSeconds(),
                config.getFailRateWarnPercent(), lagSeconds, realLag));

        LinkHealthVO vo = new LinkHealthVO();
        vo.setMetaId(meta.getId());
        vo.setMappingId(meta.getTaskId());
        vo.setMappingName(mapping.getName());
        vo.setModel(safeModelName(mapping.getModel()));
        vo.setState(meta.getState());
        vo.setStateName(stateName(meta.getState()));
        vo.setRunning(running);
        Map<String, String> snapshot = meta.getSnapshot();
        if (snapshot != null) {
            vo.setPosition(snapshot.get("position"));
            vo.setPhase(snapshot.get("fullIncrementPhase"));
        }
        vo.setLastEventTime(counters.lastEventTime);
        vo.setStallSeconds(stallSeconds);
        vo.setLagSeconds(lagSeconds);
        vo.setLagSource(realLag ? org.dbdatasync.sdk.model.ChangedOffset.EVENT_TIME_SOURCE
                : (counters.lagSource == null
                    ? org.dbdatasync.sdk.model.ChangedOffset.EVENT_TIME_UNKNOWN : counters.lagSource));
        vo.setLastSourceEventTime(counters.lastSourceEventTime);
        vo.setHasEvent(hasEvent);
        vo.setEventsTotal(counters.events);
        vo.setRetryRecovered(counters.retryRecovered);
        vo.setDeadLetters(counters.deadLetters);
        vo.setTotal(total);
        vo.setSuccess(success);
        vo.setFail(fail);
        vo.setFailRate(failRate);
        vo.setLevel(health.level.name());
        vo.setLevelLabel(health.level.getLabel());
        vo.setScore(health.score);
        vo.setReason(health.reason);
        return vo;
    }

    @Override
    public Paging<DeadLetterVO> queryDeadLetters(Map<String, String> params) {
        Map<String, String> query = new HashMap<>();
        if (params != null) {
            query.putAll(params);
        }
        if (!query.containsKey("status")) {
            query.put("status", "0");
        }
        int pageNum = parseInt(query.get("pageNum"), 1);
        int pageSize = parseInt(query.get("pageSize"), 50);

        List<DeadLetterVO> rows = new ArrayList<>();
        long total = 0;
        try {
            Paging raw = monitorService.queryAllData(query);
            if (raw != null) {
                total = raw.getTotal();
                if (raw.getData() != null) {
                    for (Object item : raw.getData()) {
                        if (!(item instanceof DataVO)) {
                            continue;
                        }
                        DataVO src = (DataVO) item;
                        DeadLetterVO vo = new DeadLetterVO();
                        vo.setId(src.getId());
                        vo.setMetaId(src.getMetaId());
                        vo.setMappingName(src.getMappingName());
                        vo.setTargetTableName(src.getTargetTableName());
                        vo.setEvent(src.getEvent());
                        vo.setError(src.getError());
                        vo.setCreateTime(src.getCreateTime());
                        String error = src.getError();
                        vo.setDeadLetter(WriteFailureTag.isDeadLetter(error));
                        vo.setSyncFail(WriteFailureTag.isSyncFail(error));
                        vo.setKind(WriteFailureTag.kindOf(error));
                        rows.add(vo);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("失败明细查询失败: {}", e.getMessage());
        }
        Paging<DeadLetterVO> result = new Paging<>(pageNum, pageSize);
        result.setTotal(total);
        result.setData(rows);
        return result;
    }

    @Override
    public int evaluateAndAlarm() {
        SystemConfig config = safeConfig();
        if (!config.isEnableReplicationAlarm()) {
            return 0;
        }
        ReplicationHealthReport report = queryHealth();
        long cooldownMillis = Math.max(30L, config.getHealthAlarmCooldownSeconds()) * 1000L;
        ReplicationHealthContent content = new ReplicationHealthContent();
        content.setTitle("实时链路健康异常");
        int count = 0;
        for (LinkHealthVO link : report.getLinks()) {
            HealthLevel level = HealthLevel.safeValueOf(link.getLevel());
            if (level == HealthLevel.IDLE || level == HealthLevel.HEALTHY) {
                continue;
            }
            // 纯「源端空闲」不制造告警噪音：无事件、无失败、无死信时只在健康页呈现
            if (!link.isHasEvent() && link.getFail() == 0 && link.getDeadLetters() == 0) {
                continue;
            }
            Long last = lastAlarmAt.get(link.getMetaId());
            long now = System.currentTimeMillis();
            if (last != null && now - last < cooldownMillis) {
                continue;
            }
            ReplicationHealthContent.ErrorItem item = new ReplicationHealthContent.ErrorItem();
            item.setName(link.getMappingName());
            item.setMetaId(link.getMetaId());
            item.setLevel(link.getLevelLabel());
            item.setStallSeconds(link.getStallSeconds());
            item.setThresholdSeconds(config.getLagCriticalSeconds());
            item.setFailRate(link.getFailRate());
            item.setDeadLetters(link.getDeadLetters());
            item.setReason(link.getReason());
            content.addErrorItem(item);
            lastAlarmAt.put(link.getMetaId(), now);
            count++;
        }
        if (count > 0) {
            try {
                sendNotifyMessage(content);
            } catch (Exception e) {
                logger.warn("链路健康告警发送失败: {}", e.getMessage());
                return 0;
            }
        }
        return count;
    }

    private SystemConfig safeConfig() {
        try {
            SystemConfig config = systemConfigService.getSystemConfig();
            return config == null ? new SystemConfig() : config;
        } catch (Exception e) {
            return new SystemConfig();
        }
    }

    private static String safeModelName(String code) {
        if (StringUtil.isBlank(code)) {
            return "";
        }
        try {
            return ModelEnum.getModelEnum(code).getName();
        } catch (Exception e) {
            return code;
        }
    }

    private static String stateName(int state) {
        for (CommonTaskStatusEnum e : CommonTaskStatusEnum.values()) {
            if (e.getCode() == state) {
                return e.getMessage();
            }
        }
        return "未知";
    }

    private static long value(java.util.concurrent.atomic.AtomicLong atomic) {
        return atomic == null ? 0L : Math.max(0L, atomic.get());
    }

    private static long firstPositive(long... candidates) {
        for (long candidate : candidates) {
            if (candidate > 0) {
                return candidate;
            }
        }
        return 0L;
    }

    private static int parseInt(String text, int fallback) {
        if (StringUtil.isBlank(text)) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
