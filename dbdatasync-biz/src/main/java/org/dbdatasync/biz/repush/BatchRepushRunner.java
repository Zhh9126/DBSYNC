/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.repush;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量重推运行器：逐批调用 {@link RepushExecutor} 执行重推并汇总结果。
 * <p>
 * 规则：
 * <ul>
 * <li>按批次顺序逐批执行，执行器返回值（&ge;0）累加进 pushedRows</li>
 * <li>执行器抛出异常或返回负数视为该批失败，批次序号计入 failedBatchIndexes，
 * 该批返回值/行数不计入 pushedRows</li>
 * <li>abortOnFailure=true 时遇到第一个失败批立即停止，后续批次不执行（aborted=true）</li>
 * <li>abortOnFailure=false 时失败后继续执行后续批次</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class BatchRepushRunner {

    /**
     * 逐批执行重推。
     *
     * @param batches        待执行的批次列表，允许为 null 或空（返回零值汇总）
     * @param executor       重推执行器，不能为 null
     * @param abortOnFailure 是否在遇到失败批次时立即中止
     * @return 执行结果汇总
     */
    public RepushRunSummary run(List<RepushBatch> batches, RepushExecutor executor, boolean abortOnFailure) {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }

        RepushRunSummary summary = new RepushRunSummary();
        List<Integer> failedBatchIndexes = new ArrayList<Integer>();
        summary.setFailedBatchIndexes(failedBatchIndexes);

        int total = batches == null ? 0 : batches.size();
        summary.setTotalBatches(total);
        if (total == 0) {
            return summary;
        }

        long pushedRows = 0L;
        int executed = 0;
        int success = 0;
        int failed = 0;

        for (RepushBatch batch : batches) {
            executed++;
            try {
                int rows = executor.push(batch);
                if (rows >= 0) {
                    success++;
                    pushedRows += rows;
                } else {
                    failed++;
                    failedBatchIndexes.add(batch.getIndex());
                }
            } catch (Exception e) {
                failed++;
                failedBatchIndexes.add(batch.getIndex());
            }

            if (failed > 0 && abortOnFailure) {
                summary.setAborted(true);
                break;
            }
        }

        summary.setExecutedBatches(executed);
        summary.setSuccessBatches(success);
        summary.setFailedBatches(failed);
        summary.setPushedRows(pushedRows);
        return summary;
    }
}
