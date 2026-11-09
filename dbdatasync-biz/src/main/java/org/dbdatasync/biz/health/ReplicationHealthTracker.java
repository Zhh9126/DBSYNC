/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.health;

import org.dbdatasync.sdk.observer.ReplicationEventObserver;
import org.dbdatasync.sdk.observer.ReplicationEventObservers;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时链路运行时指标聚合（进程内，任务级 Meta ID 维度）。
 *
 * <p>数据源是 sdk 观察点，不连库、不改存储模型。指标为运行时累计量，
 * 重启后重新累计（与 Prometheus 客户端语义一致）；位点等持久化状态仍由
 * {@code Meta.snapshot} 负责，本类不写入任何持久化数据。
 *
 * @author wuji
 * @version 1.0.0
 */
@Component
public class ReplicationHealthTracker implements ReplicationEventObserver {

    /** 单个链路的可变计数 */
    private static final class Mutable {
        final AtomicLong events = new AtomicLong();
        final AtomicLong retryRecovered = new AtomicLong();
        final AtomicLong deadLetters = new AtomicLong();
        final AtomicLong lastEventTime = new AtomicLong();
        /** Wave23：最后一个由源端日志给出的事件时间，用于端到端复制延迟 */
        final AtomicLong lastSourceEventTime = new AtomicLong();
        volatile String lagSource = org.dbdatasync.sdk.model.ChangedOffset.EVENT_TIME_UNKNOWN;
        final long firstSeenTime;
        final ConcurrentHashMap<String, AtomicLong> deadLetterByKind = new ConcurrentHashMap<>();

        Mutable(long now) {
            this.firstSeenTime = now;
        }
    }

    /** 不可变快照，供健康评估与导出消费 */
    public static final class Counters {
        public static final Counters EMPTY = new Counters(0L, 0L, 0L, 0L, 0L, 0L,
                org.dbdatasync.sdk.model.ChangedOffset.EVENT_TIME_UNKNOWN, Collections.<String, Long>emptyMap());

        public final long events;
        public final long retryRecovered;
        public final long deadLetters;
        public final long lastEventTime;
        public final long firstSeenTime;
        public final long lastSourceEventTime;
        /** source / estimated / unknown */
        public final String lagSource;
        public final Map<String, Long> deadLetterByKind;

        Counters(long events, long retryRecovered, long deadLetters, long lastEventTime,
                 long firstSeenTime, long lastSourceEventTime, String lagSource,
                 Map<String, Long> deadLetterByKind) {
            this.events = events;
            this.retryRecovered = retryRecovered;
            this.deadLetters = deadLetters;
            this.lastEventTime = lastEventTime;
            this.firstSeenTime = firstSeenTime;
            this.lastSourceEventTime = lastSourceEventTime;
            this.lagSource = lagSource;
            this.deadLetterByKind = deadLetterByKind;
        }
    }

    private final ConcurrentHashMap<String, Mutable> states = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        ReplicationEventObservers.register(this);
    }

    @PreDestroy
    public void destroy() {
        ReplicationEventObservers.unregister(this);
    }

    @Override
    public void onEvent(String metaId, String eventType, long receivedTimeMillis, String position) {
        onEvent(metaId, eventType, receivedTimeMillis, 0L,
                org.dbdatasync.sdk.model.ChangedOffset.EVENT_TIME_UNKNOWN, position);
    }

    @Override
    public void onEvent(String metaId, String eventType, long receivedTimeMillis,
                        long sourceEventTimeMillis, String eventTimeSource, String position) {
        if (metaId == null) {
            return;
        }
        long now = receivedTimeMillis > 0 ? receivedTimeMillis : System.currentTimeMillis();
        Mutable m = states.computeIfAbsent(metaId, k -> new Mutable(now));
        m.events.incrementAndGet();
        m.lastEventTime.updateAndGet(old -> Math.max(old, now));
        String source = eventTimeSource == null
                ? org.dbdatasync.sdk.model.ChangedOffset.EVENT_TIME_UNKNOWN : eventTimeSource;
        if (sourceEventTimeMillis > 0
                && org.dbdatasync.sdk.model.ChangedOffset.EVENT_TIME_SOURCE.equals(source)) {
            m.lastSourceEventTime.updateAndGet(old -> Math.max(old, sourceEventTimeMillis));
        }
        m.lagSource = source;
    }

    @Override
    public void onRetryRecovered(String metaId, int rows) {
        if (metaId == null || rows <= 0) {
            return;
        }
        states.computeIfAbsent(metaId, k -> new Mutable(System.currentTimeMillis()))
                .retryRecovered.addAndGet(rows);
    }

    @Override
    public void onDeadLetter(String metaId, String kind, String cause) {
        if (metaId == null) {
            return;
        }
        Mutable m = states.computeIfAbsent(metaId, k -> new Mutable(System.currentTimeMillis()));
        m.deadLetters.incrementAndGet();
        String key = kind == null ? "UNKNOWN" : kind;
        m.deadLetterByKind.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public Counters counters(String metaId) {
        Mutable m = states.get(metaId);
        return m == null ? Counters.EMPTY : snapshot(m);
    }

    public Map<String, Counters> allCounters() {
        Map<String, Counters> result = new HashMap<>();
        for (Map.Entry<String, Mutable> entry : states.entrySet()) {
            result.put(entry.getKey(), snapshot(entry.getValue()));
        }
        return result;
    }

    /** 仅供单测清理状态 */
    public void reset() {
        states.clear();
    }

    private static Counters snapshot(Mutable m) {
        Map<String, Long> kinds = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : m.deadLetterByKind.entrySet()) {
            kinds.put(entry.getKey(), entry.getValue().get());
        }
        return new Counters(m.events.get(), m.retryRecovered.get(), m.deadLetters.get(),
                m.lastEventTime.get(), m.firstSeenTime, m.lastSourceEventTime.get(),
                m.lagSource, Collections.unmodifiableMap(kinds));
    }
}
