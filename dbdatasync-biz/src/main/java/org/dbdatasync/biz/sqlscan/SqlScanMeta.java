package org.dbdatasync.biz.sqlscan;

/**
 * SQL 兼容度扫描的方言元数据（前端下拉 + 后端校验范围）。
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
public final class SqlScanMeta {

    private SqlScanMeta() {
    }

    /** 前端方言下拉列表（按家族分类展示） */
    public static final String[] DIALECTS = {
            "MySQL", "TiDB", "Oracle", "OceanBase", "DM",
            "PostgreSQL", "Kingbase", "SQLServer", "ClickHouse"
    };

    /** 本扫描器实际覆盖的规则家族（Oracle/PG/MySQL 三大语法家族） */
    public static final String[] COVERED_DIALECTS = {
            "MySQL", "TiDB", "Oracle", "OceanBase", "DM", "PostgreSQL", "Kingbase"
    };
}
