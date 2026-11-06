package org.dbdatasync.biz.vo;

/**
 * SQL 兼容度扫描请求。
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
public class SqlScanRequestVO {

    /** SQL 文本（可含多条语句） */
    private String sql;

    /** 源方言（连接器类型文本，如 Oracle / MySQL / PostgreSQL） */
    private String sourceDialect;

    /** 目标方言 */
    private String targetDialect;

    /** 扫描范围备注（可选，如「订单模块」） */
    private String label;

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getSourceDialect() {
        return sourceDialect;
    }

    public void setSourceDialect(String sourceDialect) {
        this.sourceDialect = sourceDialect;
    }

    public String getTargetDialect() {
        return targetDialect;
    }

    public void setTargetDialect(String targetDialect) {
        this.targetDialect = targetDialect;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
