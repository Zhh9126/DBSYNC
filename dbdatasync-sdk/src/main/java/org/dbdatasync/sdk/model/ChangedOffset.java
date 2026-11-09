package org.dbdatasync.sdk.model;

/**
 * 增量偏移量
 *
 * <p>Wave23：新增 {@code eventTime} / {@code eventTimeSource}，用于计算端到端复制延迟。
 * 旧位点 JSON 中没有这两个字段，反序列化后为 0/null，按「未知」处理，
 * 因此存量位点与 Meta.snapshot 完全兼容。</p>
 *
 * @version 1.0.0
 * @Author AE86
 * @Date 2023-08-23 20:00
 */
public final class ChangedOffset {

    /** 源端事件时间由 CDC 日志直接给出（binlog timestamp / commit timestamp / SCN 换算） */
    public static final String EVENT_TIME_SOURCE = "source";
    /** 源端事件时间不可得，用本系统接收时刻估算（不是端到端延迟） */
    public static final String EVENT_TIME_ESTIMATED = "estimated";
    /** 无有效事件时间 */
    public static final String EVENT_TIME_UNKNOWN = "unknown";

    /**
     * 驱动ID
     */
    private String metaId;

    /**
     * 增量文件名称
     */
    private String nextFileName;

    /**
     * 增量偏移量
     */
    private Object position;

    /**
     * 同步连续序号
     */
    private long seq;

    /**
     * 源端事件时间（epoch millis）。0 表示不可用。
     */
    private long eventTime;

    /**
     * 事件时间来源：source / estimated / unknown
     */
    private String eventTimeSource = EVENT_TIME_UNKNOWN;

    /** Wave23：本次事件的幂等键，由路由层计算后随位点一起提交 */
    private String idempotencyKey;

    /** Wave23：人工/自动重放的消息 ID，用于写入结果确认后再删除失败明细 */
    private String replayId;

    public String getMetaId() {
        return metaId;
    }

    public void setMetaId(String metaId) {
        this.metaId = metaId;
    }

    public String getNextFileName() {
        return nextFileName;
    }

    public void setNextFileName(String nextFileName) {
        this.nextFileName = nextFileName;
    }

    public Object getPosition() {
        return position;
    }

    public void setPosition(Object position) {
        this.position = position;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public long getEventTime() {
        return eventTime;
    }

    public void setEventTime(long eventTime) {
        this.eventTime = eventTime;
    }

    public String getEventTimeSource() {
        return eventTimeSource == null ? EVENT_TIME_UNKNOWN : eventTimeSource;
    }

    public void setEventTimeSource(String eventTimeSource) {
        this.eventTimeSource = eventTimeSource;
    }

    /** 标记为源端真实事件时间 */
    public void markSourceEventTime(long sourceTimeMillis) {
        if (sourceTimeMillis > 0) {
            this.eventTime = sourceTimeMillis;
            this.eventTimeSource = EVENT_TIME_SOURCE;
        }
    }

    /** 标记为接收时刻估算 */
    public void markEstimatedEventTime(long receivedTimeMillis) {
        this.eventTime = receivedTimeMillis > 0 ? receivedTimeMillis : System.currentTimeMillis();
        this.eventTimeSource = EVENT_TIME_ESTIMATED;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getReplayId() {
        return replayId;
    }

    public void setReplayId(String replayId) {
        this.replayId = replayId;
    }

    /** 只有 source 来源才能用于端到端 lag 判定 */
    public boolean isSourceEventTime() {
        return eventTime > 0 && EVENT_TIME_SOURCE.equals(getEventTimeSource());
    }
}
