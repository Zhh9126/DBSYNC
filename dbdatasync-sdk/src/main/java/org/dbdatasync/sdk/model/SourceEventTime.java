/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.model;

import org.dbdatasync.sdk.listener.ChangedEvent;
import org.dbdatasync.sdk.listener.event.CommonChangedEvent;

/**
 * Wave24：把各连接器取得的「源端真实事件时间」统一打到位点上。
 *
 * <p>Wave23 只有 MySQL binlog 填了 {@link ChangedOffset#EVENT_TIME_SOURCE}，
 * 其余连接器全部退化为接收时刻估算，链路健康页的「真实 lag」因此只覆盖一条路径。
 * 本工具类提供单一入口，连接器只需在发送事件前调用一次 {@link #apply(ChangedEvent, long, String)}：</p>
 *
 * <ul>
 *     <li>PostgreSQL / Kingbase：pgoutput BEGIN/COMMIT 的 PostgreSQL epoch 微秒；</li>
 *     <li>Oracle：LogMiner {@code V$LOGMNR_CONTENTS.TIMESTAMP}；</li>
 *     <li>SQL Server：{@code cdc.lsn_time_mapping.tran_end_time} 事务提交时间（批次粒度）；</li>
 *     <li>MongoDB：Change Stream clusterTime（秒）；</li>
 *     <li>Kafka：record timestamp；RocketMQ：bornTimestamp；Redis：StreamEntryID 毫秒前缀；</li>
 *     <li>文件：源文件 lastModified。</li>
 * </ul>
 *
 * <p>拿不到源端时间时传 0，本类不会把估算值伪装成 source，下游仍按 estimated 处理。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class SourceEventTime {

    /** PostgreSQL 逻辑复制时间戳起点：2000-01-01T00:00:00Z（epoch millis） */
    public static final long PG_EPOCH_MILLIS = 946_684_800_000L;

    private SourceEventTime() {
    }

    /** 把源端时间写到事件位点；millis &le; 0 视为不可用，保持原有估算标记。 */
    public static void apply(ChangedEvent event, long sourceTimeMillis, String source) {
        if (event == null || sourceTimeMillis <= 0) {
            return;
        }
        String effectiveSource = source == null ? ChangedOffset.EVENT_TIME_ESTIMATED : source;
        if (event instanceof CommonChangedEvent) {
            ((CommonChangedEvent) event).setEventTime(sourceTimeMillis, effectiveSource);
            return;
        }
        ChangedOffset offset = event.getChangedOffset();
        if (offset == null) {
            return;
        }
        if (ChangedOffset.EVENT_TIME_SOURCE.equals(effectiveSource)) {
            offset.markSourceEventTime(sourceTimeMillis);
        } else {
            offset.setEventTime(sourceTimeMillis);
            offset.setEventTimeSource(effectiveSource);
        }
    }

    /** pgoutput 的 int64 微秒（自 2000-01-01）转 epoch millis。 */
    public static long pgMicrosToEpochMillis(long micros) {
        return micros > 0 ? PG_EPOCH_MILLIS + micros / 1000L : 0L;
    }

    /** BsonTimestamp / Mongo clusterTime 的秒部分转 epoch millis。 */
    public static long secondsToMillis(long seconds) {
        return seconds > 0 ? seconds * 1000L : 0L;
    }
}
