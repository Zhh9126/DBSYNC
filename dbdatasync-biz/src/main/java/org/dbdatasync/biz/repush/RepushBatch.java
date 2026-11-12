/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.repush;

import java.util.List;

/**
 * 批次对象：一组按顺序切分出来的重推单元。
 * <p>
 * index 从 1 起编号；estimatedRows 为该批所有单元内对账明细（detail）的总数，
 * 用于预估重推的数据量。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class RepushBatch {

    /** 批次序号，从 1 开始 */
    private int index;

    /** 本批包含的重推单元 */
    private List<AggregatedRepushUnit> units;

    /** 本批预估重推行数（units 内 detail 总数） */
    private int estimatedRows;

    public RepushBatch() {
    }

    public RepushBatch(int index, List<AggregatedRepushUnit> units, int estimatedRows) {
        this.index = index;
        this.units = units;
        this.estimatedRows = estimatedRows;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public List<AggregatedRepushUnit> getUnits() {
        return units;
    }

    public void setUnits(List<AggregatedRepushUnit> units) {
        this.units = units;
    }

    public int getEstimatedRows() {
        return estimatedRows;
    }

    public void setEstimatedRows(int estimatedRows) {
        this.estimatedRows = estimatedRows;
    }
}
