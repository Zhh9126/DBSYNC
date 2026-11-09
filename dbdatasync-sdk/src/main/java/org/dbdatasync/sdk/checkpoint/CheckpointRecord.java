package org.dbdatasync.sdk.checkpoint;

/**
 * 事务型 checkpoint 记录：一次「数据写入 + 位点提交 + 幂等键」的最小原子单元。
 *
 * <p>独立于 {@code Meta.snapshot} 之外持久化，不改变任何既有存储表结构。
 * 与快照的关系：快照仍负责控制台展示与人工运维，checkpoint 负责崩溃恢复时的
 * 「已确认位点」与「最后一条已确认事件」。</p>
 */
public final class CheckpointRecord {

    private String metaId;
    private String fileName;
    private String position;
    private long seq;
    private long sourceEventTime;
    private String eventTimeSource;
    private String idempotencyKey;
    private long committedAt;

    public CheckpointRecord() {
    }

    public CheckpointRecord(String metaId, String fileName, String position, long seq,
                            long sourceEventTime, String eventTimeSource, String idempotencyKey) {
        this.metaId = metaId;
        this.fileName = fileName;
        this.position = position;
        this.seq = seq;
        this.sourceEventTime = sourceEventTime;
        this.eventTimeSource = eventTimeSource;
        this.idempotencyKey = idempotencyKey;
        this.committedAt = System.currentTimeMillis();
    }

    public String getMetaId() { return metaId; }
    public void setMetaId(String metaId) { this.metaId = metaId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public long getSourceEventTime() { return sourceEventTime; }
    public void setSourceEventTime(long sourceEventTime) { this.sourceEventTime = sourceEventTime; }
    public String getEventTimeSource() { return eventTimeSource; }
    public void setEventTimeSource(String eventTimeSource) { this.eventTimeSource = eventTimeSource; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public long getCommittedAt() { return committedAt; }
    public void setCommittedAt(long committedAt) { this.committedAt = committedAt; }
}
