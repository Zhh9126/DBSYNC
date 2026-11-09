/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlite.cdc.internal;

import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;
import org.dbdatasync.sdk.model.Field;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Wave26：SQLite Trigger CDC 方言（SQL 体触发器）。
 *
 * <p>影子表 seq 用 AUTOINCREMENT 自增主键；触发器体内逐列 {@code quote()} 拼接
 * {@code col='v'} 文本（SQLite 内建 quote 兼容引号转义与 NULL），时间戳取
 * {@code strftime('%s','now')*1000}，不依赖 JSON1 扩展。</p>
 *
 * @author DBDataSync
 * @version 2.1.6
 * @date 2026-11-09
 */
public class SQLiteTriggerCdcDialect implements TriggerCdcDialect {

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    public String createLogTableSql(String logTable) {
        return "CREATE TABLE IF NOT EXISTS " + logTable + " ("
                + "seq INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "tbl TEXT NOT NULL, op TEXT NOT NULL, pk TEXT, payload TEXT, ts INTEGER)";
    }

    @Override
    public List<String> createTriggerSqls(String logTable, String tableName, String operation, List<Field> columns) {
        String suffix = operation.toLowerCase(Locale.ROOT);
        String rowAlias = "DELETE".equalsIgnoreCase(operation) ? "OLD" : "NEW";
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                payload.append(" || ';' || ");
            }
            payload.append("'").append(columns.get(i).getName()).append("=' || quote(")
                    .append(rowAlias).append(".\"").append(columns.get(i).getName()).append("\")");
        }
        String pk = columns.get(0).getName();
        String sql = "CREATE TRIGGER IF NOT EXISTS \"__dbsync_cdc_" + suffix + "_" + sanitize(tableName) + "\""
                + " AFTER " + operation.toUpperCase(Locale.ROOT) + " ON \"" + tableName + "\" BEGIN"
                + " INSERT INTO " + logTable + "(tbl,op,pk,payload,ts) VALUES ('" + tableName + "','"
                + operation.toUpperCase(Locale.ROOT) + "', quote(" + rowAlias + ".\"" + pk + "\"), "
                + payload + ", strftime('%s','now')*1000); END";
        return Collections.singletonList(sql);
    }

    @Override
    public boolean javaBodyTriggers() {
        return false;
    }

    static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
