/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlserver.cdc.trigger.internal;

import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;
import org.dbdatasync.sdk.model.Field;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wave28：SQL Server Trigger CDC 方言（语句级触发器，inserted/deleted 虚表）。
 *
 * <p>定位：SQL Server {@code SqlServerListener} 走 CDC / LSN 轮询协议（需开启
 * 数据库 CDC 与 SQL Server Agent）；Trigger CDC 是免 CDC 组件的兜底捕获路径。</p>
 *
 * <p>实现要点：SQL Server 触发器是<b>语句级</b>（无 FOR EACH ROW），触发器体内用
 * {@code INSERT ... SELECT ... FROM inserted/deleted} 一次写入本批次全部行，
 * 轮询引擎无需感知差异；CREATE TRIGGER 必须是批内第一条语句，幂等创建用
 * {@code IF NOT EXISTS(...) EXEC('CREATE TRIGGER ...')} 包装（内部引号整体双写）；
 * 分页用 {@code SELECT TOP n}（覆盖默认 LIMIT 语法）；时间戳用
 * {@code DATEDIFF_BIG}（需 SQL Server 2016+）。</p>
 *
 * <p><b>诚实边界</b>：SQL 生成有离线单测校验形状，本机无 SQL Server 实例未真实执行；
 * 首次启用请在测试库验证 installSchema/installTriggers。</p>
 *
 * @author DBDataSync
 * @version 2.1.7
 * @date 2026-11-10
 */
public class SqlServerTriggerCdcDialect implements TriggerCdcDialect {

    @Override
    public String name() {
        return "sqlserver";
    }

    @Override
    public String createLogTableSql(String logTable) {
        // SQL Server 无 CREATE TABLE IF NOT EXISTS：IF NOT EXISTS 包装
        return "IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = '" + logTable + "')"
                + " CREATE TABLE " + logTable + " ("
                + "seq BIGINT IDENTITY(1,1) PRIMARY KEY, "
                + "tbl NVARCHAR(512) NOT NULL, op NVARCHAR(16) NOT NULL, pk NVARCHAR(512), payload NVARCHAR(MAX), ts BIGINT)";
    }

    @Override
    public List<String> createTriggerSqls(String logTable, String tableName, String operation, List<Field> columns) {
        String op = operation.toUpperCase(Locale.ROOT);
        String suffix = operation.toLowerCase(Locale.ROOT);
        String triggerName = TriggerCdcDialect.triggerSuffix(tableName, operation);
        String virtualTable = "DELETE".equalsIgnoreCase(operation) ? "deleted" : "inserted";
        String rowAlias = "D"; // 虚表统一别名

        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                payload.append(" + ';' + ");
            }
            payload.append(valueExpr(columns.get(i).getName(), rowAlias));
        }

        // EXEC 内部字符串整体双写单引号；CREATE TRIGGER 必须是批内第一条语句
        String inner = "CREATE TRIGGER " + triggerName + " ON [" + tableName + "] AFTER " + op + " AS\n"
                + "BEGIN\n"
                + "  SET NOCOUNT ON;\n"
                + "  INSERT INTO " + logTable + " (tbl,op,pk,payload,ts)\n"
                + "  SELECT '" + tableName + "', '" + op + "', CAST(" + rowAlias + ".[" + columns.get(0).getName()
                + "] AS NVARCHAR(512)), " + payload.toString() + ", "
                + "CAST(DATEDIFF_BIG(MILLISECOND, '1970-01-01 00:00:00', SYSUTCDATETIME()) AS BIGINT)\n"
                + "  FROM " + virtualTable + " " + rowAlias + ";\n"
                + "END";
        String sql = "IF NOT EXISTS (SELECT * FROM sys.triggers WHERE name = '" + triggerName + "')"
                + " EXEC('" + inner.replace("'", "''") + "')";
        List<String> sqls = new ArrayList<>();
        sqls.add(sql);
        return sqls;
    }

    @Override
    public String pollPageSql(String logTable, int batchSize) {
        return "SELECT TOP " + batchSize + " seq,tbl,op,pk,payload,ts FROM "
                + logTable + " WHERE seq > ? ORDER BY seq";
    }

    /**
     * {@code 'col=' + CASE WHEN D.[col] IS NULL THEN 'NULL' ELSE '''' + REPLACE(CAST(D.[col] AS NVARCHAR(MAX)), '''', '''''''') + '''' END}
     *
     * <p>NULL 不带引号写入；单引号双写防注入（虚表别名统一为 D）。</p>
     */
    public static String valueExpr(String column, String rowAlias) {
        String cast = "CAST(" + rowAlias + ".[" + column + "] AS NVARCHAR(MAX))";
        return "'" + column + "=' + CASE WHEN " + rowAlias + ".[" + column + "] IS NULL THEN 'NULL' "
                + "ELSE '''' + REPLACE(" + cast + ", '''', '''''''') + '''' END";
    }

    @Override
    public boolean javaBodyTriggers() {
        return false;
    }
}
