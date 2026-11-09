package org.dbdatasync.connector.sqlserver.cdc.trigger;

import org.dbdatasync.connector.sqlserver.cdc.trigger.internal.SqlServerTriggerCdcDialect;
import org.dbdatasync.sdk.model.Field;
import org.junit.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wave28：SQL Server Trigger CDC 方言 SQL 生成形状测试（离线，不连真实 SQL Server）。
 * 真实执行属诚实边界外项：首次启用需在真实 SQL Server 测试库验证（需 2016+）。
 */
public class Wave28SqlServerTriggerDialectTest {

    private final SqlServerTriggerCdcDialect dialect = new SqlServerTriggerCdcDialect();

    private List<Field> columns() {
        return Arrays.asList(
                new Field("id", "INT", Types.INTEGER),
                new Field("name", "NVARCHAR", Types.NVARCHAR));
    }

    /** 生成 n 个连续单引号（避免手数引号字面量的歧义） */
    private static String quotes(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('\'');
        }
        return sb.toString();
    }

    @Test
    public void logTableSqlIsIdempotentViaSysTables() {
        String sql = dialect.createLogTableSql("__DBSYNC_CDC_LOG");
        assertTrue("SQL Server 无 CREATE TABLE IF NOT EXISTS，必须 IF NOT EXISTS 包装",
                sql.startsWith("IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = '__DBSYNC_CDC_LOG') CREATE TABLE"));
        assertTrue(sql.contains("seq BIGINT IDENTITY(1,1) PRIMARY KEY"));
        assertTrue(sql.contains("payload NVARCHAR(MAX)"));
    }

    @Test
    public void insertTriggerIsStatementLevelFromInserted() {
        String sql = dialect.createTriggerSqls("__DBSYNC_CDC_LOG", "t1", "INSERT", columns()).get(0);
        assertTrue("CREATE TRIGGER 必须经 EXEC 动态 SQL 幂等创建", sql.contains("EXEC('CREATE TRIGGER __dbsync_cdc_insert_t1 ON [t1] AFTER INSERT AS"));
        assertTrue("语句级触发器必须从 inserted 虚表取行", sql.contains("FROM inserted D;"));
        assertTrue("EXEC 内部引号必须整体双写", sql.contains("SELECT ''t1'', ''INSERT'', CAST(D.[id] AS NVARCHAR(512))"));
        // EXEC 包装后内部引号整体再双写一层：原始 4 个引号 → 8 个、8 个 → 16 个
        assertTrue("payload REPLACE 参数翻倍（8/16/8 引号形状）",
                sql.contains("REPLACE(CAST(D.[id] AS NVARCHAR(MAX)), " + quotes(8) + ", " + quotes(16)
                        + ") + " + quotes(8) + " END"));
        assertTrue("NULL 需 CASE WHEN 兜底（双写后 ''NULL''）", sql.contains("CASE WHEN D.[name] IS NULL THEN ''NULL''"));
        assertTrue("时间戳用 DATEDIFF_BIG（2016+，EXEC 内已双写）",
                sql.contains("DATEDIFF_BIG(MILLISECOND, ''1970-01-01 00:00:00'', SYSUTCDATETIME())"));
        assertTrue(sql.endsWith("END')"));
    }

    @Test
    public void deleteTriggerUsesDeleted() {
        String sql = dialect.createTriggerSqls("__DBSYNC_CDC_LOG", "t1", "DELETE", columns()).get(0);
        assertTrue(sql.contains("AFTER DELETE AS"));
        assertTrue(sql.contains("FROM deleted D;"));
        assertFalse(sql.contains("FROM inserted"));
    }

    @Test
    public void valueExprShape() {
        String expr = SqlServerTriggerCdcDialect.valueExpr("amount", "D");
        assertEquals("'amount=' + CASE WHEN D.[amount] IS NULL THEN 'NULL' "
                + "ELSE '''' + REPLACE(CAST(D.[amount] AS NVARCHAR(MAX)), '''', '''''''') + '''' END", expr);
    }

    @Test
    public void pollPageSqlUsesTop() {
        String sql = dialect.pollPageSql("__DBSYNC_CDC_LOG", 500);
        assertTrue("SQL Server 无 LIMIT，必须 TOP 分页", sql.startsWith("SELECT TOP 500"));
        assertTrue(sql.contains("WHERE seq > ? ORDER BY seq"));
    }
}
