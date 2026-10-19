/**
 * DataSync Copyright 2026 All Rights Reserved.
 */
package org.dbdatasync.biz;

import org.dbdatasync.parser.model.FieldMapping;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.sdk.model.LineageEdge;

import java.util.List;

/**
 * 数据血缘服务
 *
 * @author AE86
 * @version 1.0.0
 * @date 2026/8/21
 */
public interface LineageService {

    /**
     * 采集血缘关系（库级 + 表级）
     *
     * @param mapping 同步任务
     */
    void collect(Mapping mapping);

    /**
     * 根据同步任务ID删除血缘关系
     *
     * @param mappingId 同步任务ID
     */
    void removeByMappingId(String mappingId);

    /**
     * 查询全部血缘关系
     *
     * @return 血缘边集合
     */
    List<LineageEdge> queryAll();

    /**
     * 下钻：按 mappingId + 源表 + 目标表，查询对应的字段映射明细
     *
     * @param mappingId    同步任务ID
     * @param sourceTable  源表名
     * @param targetTable  目标表名
     * @return 字段映射列表（含 source/target 字段名、类型、主键标记）
     */
    List<FieldMapping> getFieldMappings(String mappingId, String sourceTable, String targetTable);
}
