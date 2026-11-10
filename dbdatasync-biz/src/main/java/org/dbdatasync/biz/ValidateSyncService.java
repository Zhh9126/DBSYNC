/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz;

import org.dbdatasync.biz.vo.ValidateSyncTaskVO;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.sdk.model.Table;

import java.util.List;
import java.util.Map;

public interface ValidateSyncService {

    /**
     * 获取任务
     *
     */
    ValidateSyncTaskVO get(String id);

    /**
     * 添加任务
     *
     */
    String add(Map<String, String> params);

    /**
     * 修改任务
     *
     */
    String edit(Map<String, String> params);

    /**
     * 复制任务
     *
     */
    String copy(String id);

    /**
     * 删除任务
     *
     */
    String delete(String id);

    /**
     * 启动任务
     *
     */
    String start(String id);

    /**
     * 停止任务
     *
     */
    String stop(String id);

    /**
     * 搜索任务
     *
     */
    Paging<ValidateSyncTaskVO> search(Map<String, String> params);

    /**
     * 搜索表关系
     *
     */
    Paging<TableGroup> searchTableGroup(Map<String, String> params);

    /**
     * 分页搜索校验任务表（用于下拉框远程搜索）
     *
     */
    Paging<Table> searchTables(Map<String, String> params);

    /**
     * 获取结果
     *
     */
    Object result(String id);

    /**
     * 刷新表列表
     *
     */
    String refreshTables(String id);

    /**
     * 刷新表字段
     *
     */
    String refreshFields(String id);

    /**
     * 获取所有任务列表（用于下拉选择）
     *
     * @return 任务VO列表
     */
    List<ValidateSyncTaskVO> getAll();

    /**
     * 分页查询校验结果明细
     *
     * @param params 包含 taskId、pageNum、pageSize；可选 lite=true 列表不查 CONTENT
     * @return 分页结果
     */
    Paging searchResult(Map<String, String> params);

    /**
     * 按明细主键查询单条（含完整 CONTENT）。
     *
     * @param taskId 校验任务ID(明细分表定位)
     * @param id     明细 ID
     * @return 单条 Map，不存在返回 null
     */
    Object getValidateResultDetail(String taskId, String id);

    /**
     * 对单条校验明细中尚未订正的差异执行手动订正。
     *
     * @param taskId   校验任务ID(明细分表定位)
     * @param detailId 明细主键
     * @return 更新后的明细
     */
    Object manualReviseDetail(String taskId, String detailId);

    /**
     * 对账报告导出取数（只读）：detailId 非空时只取单条明细，否则按状态筛选分页遍历全任务。
     * <p>仅读取结果明细，不修改任何明细 / Meta / 任务配置。
     *
     * @param taskId       校验任务ID
     * @param detailStatus 状态筛选（可空）
     * @param detailId     明细ID（可空，非空时忽略分页与状态筛选）
     * @return 已铺平的明细行列表
     */
    List<Map<String, Object>> listResultDetails(String taskId, String detailStatus, String detailId);

    /**
     * Wave30：对账报告导出的流式取数（只读），逐行交给 consumer，不把全量明细堆进 List。
     * <p>与 {@link #listResultDetails} 不同，这里没有 5000 行截断，适合百万级差异明细导出；
     * 同一任务两次调用可得到同样的行序列（第一遍统计抬头、第二遍写数据行）。</p>
     *
     * @param taskId       校验任务ID
     * @param detailStatus 状态筛选（可空）
     * @param detailId     明细ID（可空，非空时只投递该 1 条）
     * @param consumer     逐行消费者，不得为 null
     */
    void scanResultDetails(String taskId, String detailStatus, String detailId,
                           java.util.function.Consumer<Map<String, Object>> consumer);

    /**
     * 对单条明细中指定下标的差异条目执行重推（只处理该 1 条）。
     *
     * @param taskId    校验任务ID
     * @param detailId  明细ID
     * @param itemIndex 0-based 差异条目下标，与对账报告「差异序号」一致
     * @return 更新后的明细
     */
    Object reviseDiffItem(String taskId, String detailId, int itemIndex);

    Map<String, Object> reviseDiffItems(String taskId, String detailId, List<Integer> itemIndexes);

    String addTableGroup(Map<String, String> params);

    String editTableGroup(Map<String, String> params);

    String removeTableGroup(String id, String ids);
}
