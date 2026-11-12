/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.observer;

import org.dbdatasync.sdk.model.ChangedOffset;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 实时链路观察点注册中心（静态、幂等、异常隔离）。
 *
 * <p>与 {@code DatabaseExecuteObservers} 同一设计：无观察者时零开销短路；
 * 任一观察者抛异常都不得影响同步主链路。
 *
 * @author wuji
 * @version 1.0.0
 */
public final class ReplicationEventObservers {

    private static final CopyOnWriteArrayList<ReplicationEventObserver> OBSERVERS =
            new CopyOnWriteArrayList<ReplicationEventObserver>();

    private ReplicationEventObservers() {
    }

    public static void register(ReplicationEventObserver observer) {
        if (observer != null && !OBSERVERS.contains(observer)) {
            OBSERVERS.addIfAbsent(observer);
        }
    }

    public static void unregister(ReplicationEventObserver observer) {
        if (observer != null) {
            OBSERVERS.remove(observer);
        }
    }

    public static void clear() {
        OBSERVERS.clear();
    }

    public static int size() {
        return OBSERVERS.size();
    }

    /** 兼容 Wave22 的旧调用：无源端事件时间 */
    public static void notifyEvent(String metaId, String eventType, long receivedTimeMillis, String position) {
        notifyEvent(metaId, eventType, receivedTimeMillis, 0L, ChangedOffset.EVENT_TIME_UNKNOWN, position);
    }

    /** Wave23：携带源端事件时间，用于端到端复制延迟 */
    public static void notifyEvent(String metaId, String eventType, long receivedTimeMillis,
                                   long sourceEventTimeMillis, String eventTimeSource, String position) {
        if (OBSERVERS.isEmpty()) {
            return;
        }
        for (ReplicationEventObserver observer : OBSERVERS) {
            try {
                observer.onEvent(metaId, eventType, receivedTimeMillis,
                        sourceEventTimeMillis, eventTimeSource, position);
            } catch (Throwable ignored) {
                // 观察点异常绝不影响同步主链路
            }
        }
    }

    public static void notifyRetryRecovered(String metaId, int rows) {
        if (OBSERVERS.isEmpty() || rows <= 0) {
            return;
        }
        for (ReplicationEventObserver observer : OBSERVERS) {
            try {
                observer.onRetryRecovered(metaId, rows);
            } catch (Throwable ignored) {
                // 观察点异常绝不影响同步主链路
            }
        }
    }

    public static void notifyDeadLetter(String metaId, String kind, String cause) {
        if (OBSERVERS.isEmpty()) {
            return;
        }
        for (ReplicationEventObserver observer : OBSERVERS) {
            try {
                observer.onDeadLetter(metaId, kind, cause);
            } catch (Throwable ignored) {
                // 观察点异常绝不影响同步主链路
            }
        }
    }

    /**
     * 位点对象转字符串，避免 sdk 之外再依赖 ChangedOffset 的具体实现。
     */
    public static String safePosition(Object offset) {
        if (offset == null) {
            return null;
        }
        try {
            return String.valueOf(offset);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 只有 source 来源的时间才返回，其余返回 0，避免把估算值当成端到端延迟 */
    public static long safeSourceEventTimeMillis(Object offset) {
        if (offset instanceof ChangedOffset) {
            ChangedOffset o = (ChangedOffset) offset;
            return o.isSourceEventTime() ? o.getEventTime() : 0L;
        }
        return 0L;
    }

    public static String safeEventTimeSource(Object offset) {
        if (offset instanceof ChangedOffset) {
            return ((ChangedOffset) offset).getEventTimeSource();
        }
        return ChangedOffset.EVENT_TIME_UNKNOWN;
    }
}
