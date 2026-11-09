/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.dameng.cdc.internal;

import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;
import org.dbdatasync.sdk.model.Field;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Wave26：达梦 Trigger CDC 方言（SQL 体触发器，Oracle 风格 :NEW/:OLD）。
 *
 * <p>定位：达梦 {@code DamengListener} 走 LogMiner 协议，需要归档模式；
 * Trigger CDC 是免归档的替代捕获路径——源表 AFTER 触发器把变更写入影子日志表。
 * payload 用 {@code REPLACE} 双写单引号 + {@code COALESCE} 处理 NULL，
 * 时间戳用 {@code (SYSDATE - 1970-01-01) * 86400000} 近似毫秒（本地时区）。</p>
 *
 * <p><b>诚实边界</b>：本方言的 SQL 生成有离线单测校验形状，但未连接真实达梦执行
 * （本机无 DM 实例）；首次在真实 DM 上启用前请先在测试库执行 installSchema/installTriggers。</p>
 *
 * @author DBDataSync
 * @version 2.1.6
 * @date 2026-11-09
 */
public class DamengTriggerCdcDialect implements TriggerCdcDialect {

    @Override
    public String name() {
        return "dameng";
    }

    @Override
    public String createLogTableSql(String logTable) {
        return "CREATE TABLE IF NOT EXISTS " + logTable + " ("
                + "seq BIGINT IDENTITY(1,1) PRIMARY KEY, "
                + "tbl VARCHAR(512) NOT NULL, op VARCHAR(16) NOT NULL, pk VARCHAR(512), payload CLOB, ts BIGINT)";
    }

    @Override
    public List<String> createTriggerSqls(String logTable, String tableName, String operation, List<Field> columns) {
        String suffix = operation.toLowerCase(Locale.ROOT);
        String rowAlias = "DELETE".equalsIgnoreCase(operation) ? ":OLD" : ":NEW";
        List<String> valueParts = new ArrayList<>();
        valueParts.add("'" + tableName + "'");
        valueParts.add("'" + operation.toUpperCase(Locale.ROOT) + "'");
        valueParts.add(rowAlias + ".\"" + columns.get(0).getName() + "\"");
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                payload.append(" || ';' || ");
            }
            payload.append(valueExpr(columns.get(i).getName(), rowAlias));
        }
        valueParts.add(payload.toString());
        // 近似 epoch 毫秒（本地时区，秒级精度足够 lag 观测）
        valueParts.add("ROUND((SYSDATE - TO_DATE('1970-01-01','YYYY-MM-DD')) * 86400000)");
        String sql = "CREATE TRIGGER IF NOT EXISTS \"__dbsync_cdc_" + suffix + "_" + sanitize(tableName) + "\""
                + " AFTER " + operation.toUpperCase(Locale.ROOT) + " ON \"" + tableName + "\" FOR EACH ROW"
                + " BEGIN INSERT INTO " + logTable + "(tbl,op,pk,payload,ts) VALUES ("
                + String.join(", ", valueParts) + "); END";
        return Collections.singletonList(sql);
    }

    /** 'col=' || COALESCE(''''||REPLACE(CAST(:NEW."col" AS VARCHAR(4000)),'''','''''')||'''','NULL') */
    public static String valueExpr(String column, String rowAlias) {
        return "'" + column + "=' || COALESCE('''' || REPLACE(CAST(" + rowAlias + ".\"" + column
                + "\" AS VARCHAR(4000)), '''', '''''') || '''', 'NULL')";
    }

    @Override
    public boolean javaBodyTriggers() {
        return false;
    }

    static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
