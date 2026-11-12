package org.dbdatasync.biz.sqlscan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 兼容度扫描支持的数据库方言。
 *
 * <p>标识与连接器 getConnectorType() 返回值对齐：
 * MySQL / Oracle / PostgreSQL / Kingbase / DM(达梦) / OceanBase / SQLServer 等，
 * 其中 PG 系（PostgreSQL/Kingbase）与 Oracle 系（Oracle/DM/OceanBase-Oracle）共享语法家族。
 * 前端下拉由 {@link SqlScanMeta#DIALECTS} 提供。
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
public enum SqlDialect {

    MYSQL("MySQL"),
    ORACLE("Oracle"),
    POSTGRESQL("PostgreSQL"),
    KINGBASE("Kingbase"),
    DM("DM"),
    OCEANBASE("OceanBase"),
    SQLSERVER("SQLServer"),
    CLICKHOUSE("ClickHouse"),
    TIDB("TiDB");

    private final String label;

    SqlDialect(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 按连接器类型文本解析方言；未知返回 null（调用方降级为「不扫描」）。
     */
    public static SqlDialect parse(String connectorType) {
        if (connectorType == null) {
            return null;
        }
        String s = connectorType.trim().toLowerCase(Locale.ROOT);
        for (SqlDialect dialect : values()) {
            if (dialect.label.toLowerCase(Locale.ROOT).equals(s)) {
                return dialect;
            }
        }
        // 达梦/金仓别名
        if (s.equals("dm")) {
            return DM;
        }
        if (s.equals("kingbase") || s.equals("kingbasees")) {
            return KINGBASE;
        }
        if (s.contains("oceanbase")) {
            return OCEANBASE;
        }
        if (s.contains("tidb")) {
            return TIDB;
        }
        return null;
    }

    /** 是否为 PG 语法家族（PostgreSQL / Kingbase） */
    public boolean isPgFamily() {
        return this == POSTGRESQL || this == KINGBASE;
    }

    /** 是否为 Oracle 语法家族（Oracle / 达梦 Oracle 兼容 / OceanBase Oracle 模式） */
    public boolean isOracleFamily() {
        return this == ORACLE || this == DM || this == OCEANBASE;
    }

    /** 是否为 MySQL 语法家族（MySQL / TiDB） */
    public boolean isMySqlFamily() {
        return this == MYSQL || this == TIDB;
    }

    private static final Set<String> DIALECT_SET = new HashSet<>();

    static {
        for (SqlDialect dialect : values()) {
            DIALECT_SET.add(dialect.label);
        }
    }

    /** 供前端下拉展示的方言标签列表 */
    public static String[] labels() {
        return Arrays.stream(values()).map(SqlDialect::getLabel).toArray(String[]::new);
    }

    @Override
    public String toString() {
        return label;
    }
}
