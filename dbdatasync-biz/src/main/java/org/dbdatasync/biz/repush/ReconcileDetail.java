/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.repush;

/**
 * 对账明细记录，表示一条需要对账/重推的数据差异。
 * <p>
 * diffType 取值约定：
 * <ul>
 * <li>missing：目标端缺失该行，需要重推插入</li>
 * <li>extra：目标端多出该行，需要清理</li>
 * <li>mismatch：两端数据不一致，需要重推覆盖</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class ReconcileDetail {

    /** 业务主键，用于跨明细聚合分组；为 null 或空串时不参与合并 */
    private String businessKey;

    /** 差异所属的物理表名 */
    private String tableName;

    /** 差异行的主键值 */
    private String pkValue;

    /** 差异类型：missing / extra / mismatch */
    private String diffType;

    /** 差异负载内容（如行数据的 JSON 快照） */
    private String payload;

    public ReconcileDetail() {
    }

    public ReconcileDetail(String businessKey, String tableName, String pkValue, String diffType, String payload) {
        this.businessKey = businessKey;
        this.tableName = tableName;
        this.pkValue = pkValue;
        this.diffType = diffType;
        this.payload = payload;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getPkValue() {
        return pkValue;
    }

    public void setPkValue(String pkValue) {
        this.pkValue = pkValue;
    }

    public String getDiffType() {
        return diffType;
    }

    public void setDiffType(String diffType) {
        this.diffType = diffType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
