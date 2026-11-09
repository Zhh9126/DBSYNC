/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.vo;

/**
 * 失败明细 / 死信视图。
 *
 * <p>{@code deadLetter=true} 表示永久故障或重试耗尽、必须人工介入；
 * {@code syncFail=true} 表示同步写入链路产生的失败（区别于校验/迁移明细）。
 *
 * @author wuji
 * @version 1.0.0
 */
public class DeadLetterVO {

    private String id;
    private String metaId;
    private String mappingName;
    private String targetTableName;
    private String event;
    private String error;
    private long createTime;
    private String kind;
    private boolean deadLetter;
    private boolean syncFail;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMetaId() {
        return metaId;
    }

    public void setMetaId(String metaId) {
        this.metaId = metaId;
    }

    public String getMappingName() {
        return mappingName;
    }

    public void setMappingName(String mappingName) {
        this.mappingName = mappingName;
    }

    public String getTargetTableName() {
        return targetTableName;
    }

    public void setTargetTableName(String targetTableName) {
        this.targetTableName = targetTableName;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public boolean isDeadLetter() {
        return deadLetter;
    }

    public void setDeadLetter(boolean deadLetter) {
        this.deadLetter = deadLetter;
    }

    public boolean isSyncFail() {
        return syncFail;
    }

    public void setSyncFail(boolean syncFail) {
        this.syncFail = syncFail;
    }
}
