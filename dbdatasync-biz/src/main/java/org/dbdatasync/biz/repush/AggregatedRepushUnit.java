/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.repush;

import java.util.List;

/**
 * 聚合后的重推单元：同一业务主键（businessKey）下的一组对账明细合并为一个整体重推。
 * <p>
 * mergedPayload 为所有明细的 payload 按 {@code "\n"} 顺序拼接的结果；
 * businessKey 为 null/空串的明细不参与合并，各自独立成一个单元（其 details 仅含自身）。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class AggregatedRepushUnit {

    /** 聚合依据的业务主键；null 或空串表示独立单元 */
    private String businessKey;

    /** 该单元包含的对账明细列表 */
    private List<ReconcileDetail> details;

    /** 所有明细 payload 按 "\n" 拼接后的合并负载 */
    private String mergedPayload;

    public AggregatedRepushUnit() {
    }

    public AggregatedRepushUnit(String businessKey, List<ReconcileDetail> details, String mergedPayload) {
        this.businessKey = businessKey;
        this.details = details;
        this.mergedPayload = mergedPayload;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public List<ReconcileDetail> getDetails() {
        return details;
    }

    public void setDetails(List<ReconcileDetail> details) {
        this.details = details;
    }

    public String getMergedPayload() {
        return mergedPayload;
    }

    public void setMergedPayload(String mergedPayload) {
        this.mergedPayload = mergedPayload;
    }
}
