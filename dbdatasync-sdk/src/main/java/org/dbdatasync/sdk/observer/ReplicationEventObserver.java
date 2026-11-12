/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.observer;

/**
 * 实时链路运行时事件观察点。
 *
 * <p>对标 Debezium / Flink CDC 的 replication lag、source idle time、
 * retry recovered、dead letter 等指标，由 sdk 主链路在「变更事件进入管道」、
 * 「自动重试挽回」、「失败明细进入死信」三个汇聚点回调。
 * sdk 只负责通知，指标聚合、阈值判定、告警由业务层实现，保持依赖方向不反转。
 *
 * @author wuji
 * @version 1.0.0
 */
public interface ReplicationEventObserver {

    /**
     * @param metaId              任务级 Meta ID
     * @param eventType           事件类型（INSERT/UPDATE/DELETE/ALTER/...）
     * @param receivedTimeMillis  事件被本系统接收的墙钟时间（毫秒）
     * @param position            源端位点（binlog file:pos / LSN / 游标，可能为 null）
     */
    void onEvent(String metaId, String eventType, long receivedTimeMillis, String position);

    /**
     * Wave23：携带源端事件时间的重载。默认委托给旧方法，保证既有观察者零改动。
     *
     * @param sourceEventTimeMillis 源端事件时间（毫秒）；0 表示不可得
     * @param eventTimeSource       source / estimated / unknown
     */
    default void onEvent(String metaId, String eventType, long receivedTimeMillis,
                         long sourceEventTimeMillis, String eventTimeSource, String position) {
        onEvent(metaId, eventType, receivedTimeMillis, position);
    }

    /**
     * 自动重试成功挽回的行数（不落失败明细，也不计入 Meta.fail）。
     *
     * @param metaId 任务级 Meta ID
     * @param rows   挽回行数
     */
    void onRetryRecovered(String metaId, int rows);

    /**
     * 一条失败明细进入死信终态（永久故障或重试耗尽）。
     *
     * @param metaId 任务级 Meta ID
     * @param kind   失败分类（TRANSIENT / PERMANENT / UNKNOWN）
     * @param cause  失败原因摘要
     */
    void onDeadLetter(String metaId, String kind, String cause);
}
