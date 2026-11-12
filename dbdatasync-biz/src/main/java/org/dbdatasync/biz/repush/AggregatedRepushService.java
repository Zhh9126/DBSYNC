package org.dbdatasync.biz.repush;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨明细聚合批量重推服务（Wave17 运行时接线层）。
 *
 * <p>把「对账差异条目」按业务键聚合 → 分批 → 逐批经 {@link DetailRepushCallback}
 * 回调既有单明细重推通道（如 {@code ValidateSyncService.reviseDiffItems}），
 * 输出批次级汇总。聚合/分批/中止语义复用 {@link DetailAggregator} /
 * {@link BatchRepushPlanner} / {@link BatchRepushRunner}。</p>
 *
 * <p>输入项 {@link ItemInput} 与既有明细重推契约对齐：taskId + detailId + itemIndex(差异序号)，
 * 聚合键为业务键 businessKey（跨明细归组）。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class AggregatedRepushService {

    /** 输入项：与既有单明细重推契约对齐 */
    public static final class ItemInput {
        /** 任务 ID */
        private final String taskId;
        /** 明细 ID */
        private final String detailId;
        /** 差异条目序号（明细内，0 起） */
        private final int itemIndex;
        /** 跨明细聚合业务键（可空：空键独立成组） */
        private final String businessKey;

        public ItemInput(String taskId, String detailId, int itemIndex, String businessKey) {
            this.taskId = taskId;
            this.detailId = detailId;
            this.itemIndex = itemIndex;
            this.businessKey = businessKey;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getDetailId() {
            return detailId;
        }

        public int getItemIndex() {
            return itemIndex;
        }

        public String getBusinessKey() {
            return businessKey;
        }
    }

    /** 单明细重推回调（批次执行时按 taskId+detailId 分组回调） */
    public interface DetailRepushCallback {
        /**
         * 重推一个明细内的若干差异条目。
         *
         * @return 该明细本次成功重推的条目数（供汇总统计）
         * @throws Exception 失败时抛出（该批记为失败）
         */
        int repush(String taskId, String detailId, List<Integer> itemIndexes) throws Exception;
    }

    /**
     * 聚合 + 分批 + 执行。
     *
     * @param items           输入项列表（可空：返回零值汇总）
     * @param maxBatchSize    每批最大单元数（&gt;0）
     * @param callback        明细重推回调，不能为 null
     * @param abortOnFailure  遇失败批是否立即中止
     * @return 批次级汇总（附 perItem 结果：itemId→成功条数；失败项不在其中）
     */
    public RepushRunSummary runAggregated(List<ItemInput> items, int maxBatchSize,
                                          DetailRepushCallback callback, boolean abortOnFailure,
                                          Map<String, Integer> perItemResults) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        List<ReconcileDetail> details = new ArrayList<ReconcileDetail>();
        if (items != null) {
            for (ItemInput item : items) {
                if (item == null) {
                    continue;
                }
                // 字段映射：tableName=taskId、pkValue=detailId、payload=itemIndex（保留溯源）
                details.add(new ReconcileDetail(item.getBusinessKey(), item.getTaskId(),
                        item.getDetailId(), "repush", String.valueOf(item.getItemIndex())));
            }
        }

        List<AggregatedRepushUnit> units = new DetailAggregator().aggregate(details);
        List<RepushBatch> batches = new BatchRepushPlanner().plan(units, maxBatchSize);

        Map<String, List<Integer>> pendingByDetail = new LinkedHashMap<String, List<Integer>>();
        for (ReconcileDetail d : details) {
            String key = d.getTableName() + "|" + d.getPkValue();
            List<Integer> indexes = pendingByDetail.get(key);
            if (indexes == null) {
                indexes = new ArrayList<Integer>();
                pendingByDetail.put(key, indexes);
            }
            try {
                indexes.add(Integer.parseInt(d.getPayload()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("差异序号非法：" + d.getPayload());
            }
        }

        RepushExecutor batchExecutor = new GroupingExecutor(pendingByDetail, callback, perItemResults);
        return new BatchRepushRunner().run(batches, batchExecutor, abortOnFailure);
    }

    /**
     * 批次执行器：把批内单元的明细还原为 (taskId,detailId)→itemIndexes 分组，逐明细回调。
     * 单元内任一明细回调失败即该批失败。
     */
    private static final class GroupingExecutor implements RepushExecutor {
        private final Map<String, List<Integer>> pendingByDetail;
        private final DetailRepushCallback callback;
        private final Map<String, Integer> perItemResults;

        GroupingExecutor(Map<String, List<Integer>> pendingByDetail,
                         DetailRepushCallback callback, Map<String, Integer> perItemResults) {
            this.pendingByDetail = pendingByDetail;
            this.callback = callback;
            this.perItemResults = perItemResults;
        }

        @Override
        public int push(RepushBatch batch) throws Exception {
            int pushed = 0;
            for (AggregatedRepushUnit unit : batch.getUnits()) {
                for (ReconcileDetail d : unit.getDetails()) {
                    String key = d.getTableName() + "|" + d.getPkValue();
                    List<Integer> indexes = pendingByDetail.get(key);
                    if (indexes == null || indexes.isEmpty()) {
                        continue;
                    }
                    int ok = callback.repush(d.getTableName(), d.getPkValue(), new ArrayList<Integer>(indexes));
                    pushed += Math.max(0, ok);
                    if (perItemResults != null) {
                        perItemResults.put(key, ok);
                    }
                    indexes.clear();
                }
            }
            return pushed;
        }
    }
}
