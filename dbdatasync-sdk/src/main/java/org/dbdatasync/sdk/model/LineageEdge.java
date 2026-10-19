/**
 * DataSync Copyright 2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.model;

import java.io.Serializable;

/**
 * 数据血缘关系边
 *
 * <p>记录一条从源端到目标端的数据流向关系，支持库级、表级两种粒度。</p>
 *
 * @author AE86
 * @version 1.0.0
 * @date 2026/8/21
 */
public class LineageEdge implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private String id;

    /**
     * 所属同步任务ID（mapping）
     */
    private String mappingId;

    /**
     * 粒度：DATABASE（库级）/ TABLE（表级）
     */
    private String granularity;

    /**
     * 源连接器ID
     */
    private String sourceConnectorId;

    /**
     * 目标连接器ID
     */
    private String targetConnectorId;

    /**
     * 源端标识（库级为库名，表级为 库.表）
     */
    private String source;

    /**
     * 目标端标识（库级为库名，表级为 库.表）
     */
    private String target;

    /**
     * 源端类型（如 mysql、oracle、file）
     */
    private String sourceType;

    /**
     * 目标端类型
     */
    private String targetType;

    /**
     * 任务名称
     */
    private String mappingName;

    /**
     * 创建时间（毫秒时间戳）
     */
    private Long createTime;

    /**
     * 更新时间（毫秒时间戳）
     */
    private Long updateTime;

    public LineageEdge() {
    }

    public LineageEdge(String mappingId, String granularity, String sourceConnectorId,
                       String targetConnectorId, String source, String target, String sourceType,
                       String targetType, String mappingName) {
        this.mappingId = mappingId;
        this.granularity = granularity;
        this.sourceConnectorId = sourceConnectorId;
        this.targetConnectorId = targetConnectorId;
        this.source = source;
        this.target = target;
        this.sourceType = sourceType;
        this.targetType = targetType;
        this.mappingName = mappingName;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMappingId() {
        return mappingId;
    }

    public void setMappingId(String mappingId) {
        this.mappingId = mappingId;
    }

    public String getGranularity() {
        return granularity;
    }

    public void setGranularity(String granularity) {
        this.granularity = granularity;
    }

    public String getSourceConnectorId() {
        return sourceConnectorId;
    }

    public void setSourceConnectorId(String sourceConnectorId) {
        this.sourceConnectorId = sourceConnectorId;
    }

    public String getTargetConnectorId() {
        return targetConnectorId;
    }

    public void setTargetConnectorId(String targetConnectorId) {
        this.targetConnectorId = targetConnectorId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getMappingName() {
        return mappingName;
    }

    public void setMappingName(String mappingName) {
        this.mappingName = mappingName;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }
}
