/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.repush;

import java.util.ArrayList;
import java.util.List;

/**
 * 批次规划器：将聚合后的重推单元按顺序切分为多个 {@link RepushBatch}。
 * <p>
 * 规则：
 * <ul>
 * <li>按入参顺序依次切批，每批最多 maxBatchSize 个单元</li>
 * <li>最后一批可小于 maxBatchSize</li>
 * <li>空输入返回空列表</li>
 * <li>maxBatchSize &lt;= 0 抛 {@link IllegalArgumentException}</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class BatchRepushPlanner {

    /**
     * 按顺序将重推单元切分为批次。
     *
     * @param units        聚合后的重推单元列表，允许为 null 或空（返回空列表）
     * @param maxBatchSize 每批最大单元数，必须大于 0
     * @return 批次列表，index 从 1 起连续编号
     * @throws IllegalArgumentException 当 maxBatchSize &lt;= 0 时抛出
     */
    public List<RepushBatch> plan(List<AggregatedRepushUnit> units, int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive, got: " + maxBatchSize);
        }
        List<RepushBatch> batches = new ArrayList<RepushBatch>();
        if (units == null || units.isEmpty()) {
            return batches;
        }

        int total = units.size();
        int index = 1;
        for (int start = 0; start < total; start += maxBatchSize) {
            int end = Math.min(start + maxBatchSize, total);
            List<AggregatedRepushUnit> slice = new ArrayList<AggregatedRepushUnit>(units.subList(start, end));
            batches.add(new RepushBatch(index, slice, countRows(slice)));
            index++;
        }
        return batches;
    }

    /**
     * 统计批内所有单元的明细总数作为预估行数。
     */
    private int countRows(List<AggregatedRepushUnit> slice) {
        int rows = 0;
        for (AggregatedRepushUnit unit : slice) {
            if (unit.getDetails() != null) {
                rows += unit.getDetails().size();
            }
        }
        return rows;
    }
}
