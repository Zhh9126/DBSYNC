package org.dbdatasync.connector.dameng.cdc;

import org.dbdatasync.connector.dameng.cdc.internal.DamengTriggerCdcDialect;
import org.dbdatasync.sdk.model.Field;
import org.junit.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wave26：达梦 Trigger CDC 方言 SQL 生成形状测试（离线，不连真实 DM）。
 * 真实执行属诚实边界外项：首次启用需在真实 DM 测试库验证。
 */
public class Wave26DamengTriggerDialectTest {

    private final DamengTriggerCdcDialect dialect = new DamengTriggerCdcDialect();

    private List<Field> columns() {
        return Arrays.asList(
                new Field("id", "INT", Types.INTEGER),
                new Field("name", "VARCHAR", Types.VARCHAR));
    }

    @Test
    public void logTableSqlUsesIdentity() {
        String sql = dialect.createLogTableSql("__DBSYNC_CDC_LOG");
        assertTrue(sql.contains("seq BIGINT IDENTITY(1,1) PRIMARY KEY"));
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS"));
    }

    @Test
    public void insertTriggerUsesNewAliasAndQuoting() {
        String sql = dialect.createTriggerSqls("__DBSYNC_CDC_LOG", "T1", "INSERT", columns()).get(0);
        assertTrue(sql.contains("AFTER INSERT ON \"T1\" FOR EACH ROW"));
        assertTrue(sql.contains(":NEW.\"id\""));
        assertTrue("payload 需双写单引号防注入", sql.contains("REPLACE(CAST(:NEW.\"name\" AS VARCHAR(4000)), '''', '''''')"));
        assertTrue("NULL 值需 COALESCE 兜底", sql.contains("COALESCE"));
        assertTrue(sql.contains("ROUND((SYSDATE - TO_DATE('1970-01-01','YYYY-MM-DD')) * 86400000)"));
        assertTrue(sql.endsWith("; END"));
    }

    @Test
    public void deleteTriggerUsesOldAlias() {
        String sql = dialect.createTriggerSqls("__DBSYNC_CDC_LOG", "T1", "DELETE", columns()).get(0);
        assertTrue(sql.contains("AFTER DELETE ON \"T1\""));
        assertTrue(sql.contains(":OLD.\"id\""));
        assertFalse(sql.contains(":NEW"));
    }

    @Test
    public void valueExprShape() {
        String expr = DamengTriggerCdcDialect.valueExpr("amount", ":NEW");
        assertEquals("'amount=' || COALESCE('''' || REPLACE(CAST(:NEW.\"amount\" AS VARCHAR(4000)), '''', '''''') || '''', 'NULL')", expr);
    }
}
