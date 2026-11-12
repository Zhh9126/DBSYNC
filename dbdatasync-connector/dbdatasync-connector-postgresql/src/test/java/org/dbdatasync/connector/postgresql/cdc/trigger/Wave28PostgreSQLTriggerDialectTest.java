package org.dbdatasync.connector.postgresql.cdc.trigger;

import org.dbdatasync.connector.postgresql.cdc.trigger.internal.PostgreSQLTriggerCdcDialect;
import org.dbdatasync.sdk.model.Field;
import org.junit.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Wave28：PostgreSQL Trigger CDC 方言 SQL 生成形状测试（离线，不连真实 PG）。
 * 真实执行属诚实边界外项：首次启用需在真实 PG 测试库验证。
 */
public class Wave28PostgreSQLTriggerDialectTest {

    private final PostgreSQLTriggerCdcDialect dialect = new PostgreSQLTriggerCdcDialect();

    private List<Field> columns() {
        return Arrays.asList(
                new Field("id", "INT4", Types.INTEGER),
                new Field("name", "VARCHAR", Types.VARCHAR));
    }

    @Test
    public void logTableSqlUsesBigserial() {
        String sql = dialect.createLogTableSql("__DBSYNC_CDC_LOG");
        assertTrue(sql.contains("seq BIGSERIAL PRIMARY KEY"));
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS"));
    }

    @Test
    public void triggerIsFunctionPlusTriggerPair() {
        List<String> sqls = dialect.createTriggerSqls("__DBSYNC_CDC_LOG", "t1", "INSERT", columns());
        assertEquals("PG 触发器 = 函数 + 触发器两条 DDL", 2, sqls.size());
        String fn = sqls.get(0);
        String tr = sqls.get(1);
        assertTrue("函数体必须 $fn$ 美元引号包裹", fn.contains("AS $fn$") && fn.endsWith("$fn$ LANGUAGE plpgsql;"));
        assertTrue(fn.contains("CREATE OR REPLACE FUNCTION __dbsync_cdc_fn_insert_t1() RETURNS trigger"));
        assertTrue(fn.contains("NEW.\"id\"::text"));
        assertTrue("payload 需 replace 双写单引号", fn.contains("replace(NEW.\"name\"::text, '''', '''''')"));
        assertTrue("NULL 需 CASE WHEN 兜底且不带引号", fn.contains("CASE WHEN NEW.\"name\" IS NULL THEN 'NULL'"));
        assertTrue(fn.contains("(extract(epoch from clock_timestamp()) * 1000)::bigint"));
        assertTrue("触发器挂接函数", tr.contains("CREATE TRIGGER __dbsync_cdc_insert_t1 AFTER INSERT ON \"t1\" FOR EACH ROW EXECUTE FUNCTION __dbsync_cdc_fn_insert_t1();"));
    }

    @Test
    public void deleteTriggerUsesOldAlias() {
        List<String> sqls = dialect.createTriggerSqls("__DBSYNC_CDC_LOG", "t1", "DELETE", columns());
        assertEquals(2, sqls.size());
        assertTrue(sqls.get(0).contains("OLD.\"id\"::text"));
        assertTrue(sqls.get(1).contains("AFTER DELETE ON \"t1\""));
        assertTrue(sqls.get(0).contains("__dbsync_cdc_fn_delete_t1"));
    }

    @Test
    public void valueExprShape() {
        String expr = PostgreSQLTriggerCdcDialect.valueExpr("amount", "NEW");
        assertEquals("'amount=' || CASE WHEN NEW.\"amount\" IS NULL THEN 'NULL' "
                + "ELSE '''' || replace(NEW.\"amount\"::text, '''', '''''') || '''' END", expr);
    }
}
