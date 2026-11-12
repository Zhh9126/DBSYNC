/**
 * DBDataSync Copyright 2019-2024 All Rights Reserved.
 */
package org.dbdatasync.sdk.listener.event;

import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.sdk.listener.ChangedEvent;
import org.dbdatasync.sdk.model.ChangedOffset;

/**
 * 通用变更事件
 *
 * <p>Wave23：所有变更事件统一携带事件时间。若连接器未显式给出源端时间
 * （binlog timestamp / commit timestamp / SCN），则退化为「接收时刻估算」并明确标记，
 * 保证下游不会把估算值冒充端到端延迟。</p>
 *
 * @version 1.0.0
 * @Author AE86
 * @Date 2023-08-20 20:00
 */
public abstract class CommonChangedEvent implements ChangedEvent {

    /**
     * traceId
     */
    private String traceId = StringUtil.EMPTY;

    /**
     * 变更表名称
     */
    private String sourceTableName;

    /**
     * 变更事件
     */
    private String event;

    /**
     * 增量偏移量
     */
    private final ChangedOffset changedOffset = new ChangedOffset();

    @Override
    public String getTraceId() {
        return traceId;
    }

    @Override
    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    @Override
    public String getSourceTableName() {
        return sourceTableName;
    }

    @Override
    public void setSourceTableName(String sourceTableName) {
        this.sourceTableName = sourceTableName;
    }

    @Override
    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    @Override
    public ChangedOffset getChangedOffset() {
        // 兜底：任何路径取位点时都保证有事件时间（估算），避免下游拿到 0 后误判
        if (changedOffset.getEventTime() <= 0) {
            changedOffset.markEstimatedEventTime(System.currentTimeMillis());
        }
        return changedOffset;
    }

    public void setNextFileName(String nextFileName) {
        changedOffset.setNextFileName(nextFileName);
    }

    public void setPosition(Object position) {
        changedOffset.setPosition(position);
        if (changedOffset.getEventTime() <= 0) {
            changedOffset.markEstimatedEventTime(System.currentTimeMillis());
        }
    }

    /** 由 CDC Listener 填充源端真实事件时间 */
    public void setEventTime(long sourceTimeMillis, String source) {
        if (ChangedOffset.EVENT_TIME_SOURCE.equals(source)) {
            changedOffset.markSourceEventTime(sourceTimeMillis);
        } else if (sourceTimeMillis > 0) {
            changedOffset.setEventTime(sourceTimeMillis);
            changedOffset.setEventTimeSource(source);
        }
    }

    public long getEventTime() {
        return getChangedOffset().getEventTime();
    }

    public String getEventTimeSource() {
        return getChangedOffset().getEventTimeSource();
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
