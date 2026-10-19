/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.biz;

import org.dbdatasync.biz.model.AppReportMetric;
import org.dbdatasync.biz.model.DashboardMetric;
import org.dbdatasync.biz.model.MetricResponse;
import org.dbdatasync.biz.vo.MetaVO;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.storage.enums.StorageDataStatusEnum;

import java.util.List;
import java.util.Map;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2020/1/14 0:02
 */
public interface MonitorService {

    /**
     * 分页查询驱动元信息（供监控页下拉远程搜索）
     *
     * @param params pageNum、pageSize、searchKey
     * @return 分页结果
     */
    Paging<MetaVO> queryMeta(Map<String, String> params);

    /**
     * 获取驱动元信息
     *
     * @param metaId
     * @return
     */
    MetaVO getMetaVo(String metaId);

    /**
     * 获取驱动默认元信息id
     *
     * @param params
     * @return
     */
    String getDefaultMetaId(Map<String, String> params);

    /**
     * 查询驱动同步数据
     *
     * @param params
     * @return
     */
    Paging queryData(Map<String, String> params);

    /**
     * 跨驱动聚合查询同步数据明细（仪表盘下钻）。
     * 支持按事件类型（INSERT/UPDATE/UPSERT/DELETE/ALTER）与成功状态（1/0）过滤，
     * 行中附带 mappingName、sourceTableName、targetTableName、metaId。
     *
     * @param params pageNum、pageSize、status、event
     * @return
     */
    Paging queryAllData(Map<String, String> params);

    /**
     * 清空驱动同步数据
     *
     * @param id 任务 Meta ID
     * @return
     */
    String clearData(String id);

    /**
     * 清空驱动同步数据；tableGroupId 非空时仅清空该表映射下明细与表级 Meta。
     *
     * @param id           任务 Meta ID
     * @param tableGroupId 表映射 ID，空则清空全部
     * @return
     */
    String clearData(String id, String tableGroupId);

    /**
     * 查询操作日志
     *
     * @param params
     * @return
     */
    Paging queryLog(Map<String, String> params);

    /**
     * 清空操作日志
     *
     * @return
     */
    String clearLog();

    /**
     * 删除过期的数据和日志
     */
    void deleteExpiredDataAndLog();

    /**
     * 获取所有同步数据状态类型
     *
     * @return
     */
    List<StorageDataStatusEnum> getStorageDataStatusEnumAll();

    /**
     * 获取应用报告
     *
     * @return
     */
    AppReportMetric queryAppMetric(List<MetricResponse> metrics);

    /**
     * 获取仪表盘报告
     *
     * @return
     */
    DashboardMetric queryDashboardMetric();

    /**
     * 查询表执行器
     *
     * @return
     */
    Paging<MetricResponse> queryActuator(Map<String, String> params);
}
