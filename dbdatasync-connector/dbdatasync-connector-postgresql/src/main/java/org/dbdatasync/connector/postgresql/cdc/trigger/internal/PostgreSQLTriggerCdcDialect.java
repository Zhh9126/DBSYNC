/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.postgresql.cdc.trigger.internal;

import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;
import org.dbdatasync.sdk.model.Field;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wave28：PostgreSQL Trigger CDC 方言（PL/pgSQL 函数体触发器）。
 *
 * <p>定位：PostgreSQL {@code PostgreSQLListener} 走逻辑复制（wal2json/测试解码槽，
 * 需 wal_level=logical 与 REPLICA IDENTITY）；Trigger CDC 是免逻辑复制的兜底
 * 捕获路径。人大金仓（PG 兼容内核）在 Oracle 兼容模式外的 PG 模式下语法同构。</p>
 *
 * <p>实现要点：PostgreSQL 触发器体必须挂在函数上，因此每表每操作生成
 * 「函数 + 触发器」两条 DDL（{@link #createTriggerSqls} 返回列表）；函数体用
 * {@code $fn$} 美元引号包裹避免单引号嵌套转义；NULL 用 CASE WHEN 不带引号写入；
 * 触发器名表内唯一即可，函数名带表名防跨表冲突。</p>
 *
 * <p><b>诚实边界</b>：SQL 生成有离线单测校验形状，本机无 PostgreSQL 实例未真实执行；
 * 首次启用请在测试库验证 installSchema/installTriggers。</p>
 *
 * @author DBDataSync
 * @version 2.1.7
 * @date 2026-11-10
 */
public class PostgreSQLTriggerCdcDialect implements TriggerCdcDialect {

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public String createLogTableSql(String logTable) {
        return "CREATE TABLE IF NOT EXISTS " + logTable + " ("
                + "seq BIGSERIAL PRIMARY KEY, "
                + "tbl VARCHAR(512) NOT NULL, op VARCHAR(16) NOT NULL, pk VARCHAR(512), payload TEXT, ts BIGINT)";
    }

    @Override
    public List<String> createTriggerSqls(String logTable, String tableName, String operation, List<Field> columns) {
        String op = operation.toUpperCase(Locale.ROOT);
        String suffix = operation.toLowerCase(Locale.ROOT);
        String triggerName = TriggerCdcDialect.triggerSuffix(tableName, operation);
        String functionName = "__dbsync_cdc_fn_" + suffix + "_" + sanitize(tableName);
        String rowAlias = "DELETE".equalsIgnoreCase(operation) ? "OLD" : "NEW";

        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                payload.append(" || ';' || ");
            }
            payload.append(valueExpr(columns.get(i).getName(), rowAlias));
        }

        String function = "CREATE OR REPLACE FUNCTION " + functionName + "() RETURNS trigger AS $fn$\n"
                + "BEGIN\n"
                + "  INSERT INTO " + logTable + "(tbl,op,pk,payload,ts) VALUES ("
                + "'" + tableName + "', '" + op + "', " + rowAlias + ".\"" + columns.get(0).getName()
                + "\"::text, " + payload.toString() + ", "
                + "(extract(epoch from clock_timestamp()) * 1000)::bigint);\n"
                + "  RETURN NULL;\n"
                + "END;\n"
                + "$fn$ LANGUAGE plpgsql;";
        String trigger = "CREATE TRIGGER " + triggerName + " AFTER " + op
                + " ON \"" + tableName + "\" FOR EACH ROW EXECUTE FUNCTION " + functionName + "();";
        List<String> sqls = new ArrayList<>();
        sqls.add(function);
        sqls.add(trigger);
        return sqls;
    }

    /**
     * {@code 'col=' || CASE WHEN NEW."col" IS NULL THEN 'NULL' ELSE '''' || replace(NEW."col"::text,'''','''''') || '''' END}
     *
     * <p>NULL 不带引号写入；函数体在 $fn$ 美元引号内，单引号只需 SQL 级双写。</p>
     */
    public static String valueExpr(String column, String rowAlias) {
        String cast = rowAlias + ".\"" + column + "\"::text";
        return "'" + column + "=' || CASE WHEN " + rowAlias + ".\"" + column + "\" IS NULL THEN 'NULL' "
                + "ELSE '''' || replace(" + cast + ", '''', '''''') || '''' END";
    }

    @Override
    public boolean javaBodyTriggers() {
        return false;
    }

    static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
