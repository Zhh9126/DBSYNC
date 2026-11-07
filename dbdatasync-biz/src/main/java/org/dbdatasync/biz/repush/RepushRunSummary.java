/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.repush;

/**
 * 批量重推执行结果汇总。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class RepushRunSummary {

    /** 规划出的总批次数 */
    private int totalBatches;

    /** 实际执行过的批次数 */
    private int executedBatches;

    /** 执行成功的批次数 */
    private int successBatches;

    /** 执行失败的批次数 */
    private int failedBatches;

    /** 失败批次的序号列表（index 从 1 起，按执行顺序） */
    private java.util.List<Integer> failedBatchIndexes;

    /** 累计成功重推的行数 */
    private long pushedRows;

    /** 是否因失败而中止（abortOnFailure=true 时遇到失败立即停止） */
    private boolean aborted;

    public RepushRunSummary() {
    }

    public int getTotalBatches() {
        return totalBatches;
    }

    public void setTotalBatches(int totalBatches) {
        this.totalBatches = totalBatches;
    }

    public int getExecutedBatches() {
        return executedBatches;
    }

    public void setExecutedBatches(int executedBatches) {
        this.executedBatches = executedBatches;
    }

    public int getSuccessBatches() {
        return successBatches;
    }

    public void setSuccessBatches(int successBatches) {
        this.successBatches = successBatches;
    }

    public int getFailedBatches() {
        return failedBatches;
    }

    public void setFailedBatches(int failedBatches) {
        this.failedBatches = failedBatches;
    }

    public java.util.List<Integer> getFailedBatchIndexes() {
        return failedBatchIndexes;
    }

    public void setFailedBatchIndexes(java.util.List<Integer> failedBatchIndexes) {
        this.failedBatchIndexes = failedBatchIndexes;
    }

    public long getPushedRows() {
        return pushedRows;
    }

    public void setPushedRows(long pushedRows) {
        this.pushedRows = pushedRows;
    }

    public boolean isAborted() {
        return aborted;
    }

    public void setAborted(boolean aborted) {
        this.aborted = aborted;
    }
}
