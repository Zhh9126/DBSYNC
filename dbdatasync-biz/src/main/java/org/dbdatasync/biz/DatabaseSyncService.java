/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz;

import org.dbdatasync.biz.vo.DatabaseMappingVO;
import org.dbdatasync.biz.vo.DatabaseSyncTaskVO;
import org.dbdatasync.biz.vo.TablePreviewVO;
import org.dbdatasync.common.model.Paging;

import java.util.List;
import java.util.Map;

/**
 * 整库迁移业务服务
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-05-22 00:00
 */
public interface DatabaseSyncService {

    /**
     * 获取任务详情
     *
     * @param id 任务 ID
     * @return 任务 VO
     */
    DatabaseSyncTaskVO get(String id);

    /**
     * 新增任务
     *
     * @param params 任务参数
     * @return 任务 ID
     */
    String add(Map<String, String> params);

    /**
     * 修改任务
     *
     * @param params 任务参数（含 id）
     * @return 任务 ID
     */
    String edit(Map<String, String> params);

    /**
     * 删除任务
     *
     * @param id 任务 ID
     * @return 操作结果提示
     */
    String delete(String id);

    /**
     * 启动任务
     *
     * @param id 任务 ID
     * @return 操作结果提示
     */
    String start(String id);

    /**
     * 停止任务
     *
     * @param id 任务 ID
     * @return 操作结果提示
     */
    String stop(String id);

    /**
     * 分页搜索任务列表
     *
     * @param params 查询参数（pageNum、pageSize、searchKey）
     * @return 分页结果
     */
    Paging<DatabaseSyncTaskVO> search(Map<String, String> params);

    /**
     * 预览连接器下表列表（滚动分页）
     *
     * @param params connectorId、database、schema、searchKey、offset、limit
     * @return 分页数据及类型统计
     */
    TablePreviewVO previewTables(Map<String, String> params);

    /**
     * 全部任务（详情页任务下拉）
     */
    List<DatabaseSyncTaskVO> getAll();

    /**
     * 一键接续增量：为整库迁移任务创建增量（CDC/定时）驱动。
     * 每组「源连接库 -> 目标连接库」生成一个库级增量驱动（模型=increment），
     * 表映射/字段映射/执行命令沿用迁移结果（目标表由全量阶段自动创建）。
     *
     * @param id        整库迁移任务 ID
     * @param autoStart 创建后是否立即启动增量驱动
     * @return 创建（并启动）的驱动 ID 列表
     */
    List<String> upgradeToIncrement(String id, boolean autoStart);

    /**
     * 实例级一键映射：按源连接器实例枚举全部业务库（内置系统库过滤），
     * 逐库生成「源库 -> 目标库」映射对（目标库同名，可选前缀 targetDatabasePrefix）。
     *
     * @return 库映射 VO 列表（供合并进新增任务的库映射）
     */
    List<DatabaseMappingVO> expandDatabases(Map<String, String> params);

    /**
     * 分页查询迁移结果明细
     */
    Paging searchResult(Map<String, String> params);
}
