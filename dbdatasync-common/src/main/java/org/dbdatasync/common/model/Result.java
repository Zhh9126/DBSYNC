package org.dbdatasync.common.model;

import java.util.LinkedList;
import java.util.List;

public class Result<T> {

    /**
     * 成功数据
     */
    private final List<T> successData = new LinkedList<>();

    /**
     * 错误数据
     */
    private final List<T> failData = new LinkedList<>();

    /**
     * 错误日志
     */
    private final StringBuffer error = new StringBuffer();

    /**
     * 驱动表映射关系ID
     */
    private String tableGroupId;

    /**
     * 目标表名称
     */
    private String targetTableGroupName;

    /**
     * 同步任务metaId
     */
    private String metaId;

    /**
     * 事件
     */
    private String event;
    /** Wave23：重放消息 ID，由写入完成点回传结果 */
    private String replayId;

    private final Object LOCK = new Object();

    /**
     * 自动重试成功挽回的行数（可观测性指标）。
     * <p>这类行不进入 failData、不落失败明细、不计入 Meta.fail，
     * 但必须可观测，否则「重试是否真的在起作用」无法度量。
     */
    private final java.util.concurrent.atomic.AtomicInteger autoRetriedRows =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Wave24：逐行失败分类，key 为行内容快照（{@code String.valueOf(row)}）。
     * 写入链路按行填充，落失败明细时按行覆盖批次标签，避免「整批永久优先」误伤可自愈行。
     */
    private final java.util.Map<String, RowFailure> rowFailures = new java.util.concurrent.ConcurrentHashMap<>();

    public Result() {
    }

    public Result(List<T> data) {
        this.successData.addAll(data);
    }

    public List<T> getSuccessData() {
        return successData;
    }

    public List<T> getFailData() {
        return failData;
    }

    public StringBuffer getError() {
        return error;
    }

    /**
     * 线程安全添加集合
     */
    public void addFailData(List failData) {
        synchronized (LOCK) {
            this.failData.addAll(failData);
        }
    }

    /**
     * 线程安全添加集合
     */
    public void addSuccessData(List successData) {
        synchronized (LOCK) {
            this.successData.addAll(successData);
        }
    }

    public String getTableGroupId() {
        return tableGroupId;
    }

    public void setTableGroupId(String tableGroupId) {
        this.tableGroupId = tableGroupId;
    }

    public String getTargetTableGroupName() {
        return targetTableGroupName;
    }

    public void setTargetTableGroupName(String targetTableGroupName) {
        this.targetTableGroupName = targetTableGroupName;
    }

    public String getMetaId() {
        return metaId;
    }

    public void setMetaId(String metaId) {
        this.metaId = metaId;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getReplayId() {
        return replayId;
    }

    public void setReplayId(String replayId) {
        this.replayId = replayId;
    }

    public java.util.concurrent.atomic.AtomicInteger getAutoRetriedRows() {
        return autoRetriedRows;
    }

    public java.util.Map<String, RowFailure> getRowFailures() {
        return rowFailures;
    }

    public void recordRowFailure(String rowKey, RowFailure failure) {
        if (rowKey != null && failure != null) {
            rowFailures.put(rowKey, failure);
        }
    }
}
