package org.dbdatasync.biz.sqlscan;

/**
 * DDL 变更单元（ALTER TABLE 拆解后的单列操作单元）。
 *
 * <p>由 {@link DdlChangeSplitter} 通过正则从 ALTER TABLE 语句中拆解得到，
 * 每条单元对应一个 ADD / MODIFY / CHANGE / DROP / RENAME 操作，
 * 并记录类型映射结果与风险级别。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class DdlChangeUnit {

    /** ADD / MODIFY / CHANGE / DROP / RENAME / UNKNOWN */
    private String operation;

    /** 目标列名（CHANGE/RENAME 用 newName） */
    private String columnName;

    /** CHANGE/RENAME 的源列名（其它为空串） */
    private String oldName;

    /** 源类型片段（ADD/MODIFY/CHANGE 有值；DROP/RENAME 空） */
    private String sourceType;

    /** 映射后目标类型（DROP="(drop)"；UNKNOWN 空串） */
    private String targetType;

    /** info / warn / error（复用 SqlCompatibilityIssue.LEVEL_*） */
    private String level;

    /** 风险/兼容度提示 */
    private String riskNote;

    public DdlChangeUnit() {
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getOldName() {
        return oldName;
    }

    public void setOldName(String oldName) {
        this.oldName = oldName;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getRiskNote() {
        return riskNote;
    }

    public void setRiskNote(String riskNote) {
        this.riskNote = riskNote;
    }
}
