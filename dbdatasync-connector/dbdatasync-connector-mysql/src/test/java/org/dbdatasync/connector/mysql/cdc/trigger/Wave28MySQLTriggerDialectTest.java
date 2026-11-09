package org.dbdatasync.connector.mysql.cdc.trigger;

import org.dbdatasync.connector.mysql.cdc.trigger.internal.MySQLTriggerCdcDialect;
import org.dbdatasync.sdk.model.Field;
import org.junit.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wave28：MySQL Trigger CDC 方言 SQL 生成形状测试（离线，不连真实 MySQL）。
 * 真实执行属诚实边界外项：首次启用需在真实 MySQL 测试库验证。
 */
public class Wave28MySQLTriggerDialectTest {

    private final MySQLTriggerCdcDialect dialect = new MySQLTriggerCdcDialect();

    private List<Field> columns() {
        return Arrays.asList(
                new Field("id", "INT", Types.INTEGER),
                new Field("name", "VARCHAR", Types.VARCHAR));
    }

    @Test
    public void logTableSqlUsesAutoIncrement() {
        String sql = dialect.createLogTableSql("__DBSYNC_CDC_LOG");
        assertTrue(sql.contains("seq BIGINT AUTO_INCREMENT PRIMARY KEY"));
        assertTrue(sql.contains("ENGINE=InnoDB"));
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS"));
    }

    @Test
    public void insertTriggerUsesNewAliasAndBacktickQuoting() {
        String sql = dialect.createTriggerSqls("__DBSYNC_CDC_LOG", "t1", "INSERT", columns()).get(0);
        assertTrue("触发器无需 DELIMITER/BEGIN END（JDBC 单条执行）", sql.contains("AFTER INSERT ON `t1` FOR EACH ROW INSERT INTO"));
        assertTrue(sql.contains("CAST(NEW.`id` AS CHAR)"));
        assertTrue("payload 需转义反斜杠与单引号", sql.contains("REPLACE(REPLACE(CAST(NEW.`name` AS CHAR), '\\\\', '\\\\\\\\'), '''', '''''')"));
        assertTrue("NULL 需 IF 兜底且不带引号", sql.contains("IF(NEW.`name` IS NULL, 'NULL'"));
        assertTrue(sql.contains("ROUND(UNIX_TIMESTAMP(NOW(3)) * 1000)"));
        assertFalse("单条 INSERT 不得含分号体", sql.contains("BEGIN"));
    }

    @Test
    public void deleteTriggerUsesOldAlias() {
        String sql = dialect.createTriggerSqls("__DBSYNC_CDC_LOG", "t1", "DELETE", columns()).get(0);
        assertTrue(sql.contains("AFTER DELETE ON `t1`"));
        assertTrue(sql.contains("OLD.`id`"));
        assertFalse(sql.contains("NEW."));
    }

    @Test
    public void valueExprShape() {
        String expr = MySQLTriggerCdcDialect.valueExpr("amount", "NEW");
        assertEquals("'amount=', IF(NEW.`amount` IS NULL, 'NULL', "
                + "CONCAT('''', REPLACE(REPLACE(CAST(NEW.`amount` AS CHAR), '\\\\', '\\\\\\\\'), '''', ''''''), ''''))", expr);
    }

    @Test
    public void pollPageSqlUsesLimit() {
        String sql = dialect.pollPageSql("__DBSYNC_CDC_LOG", 500);
        assertTrue(sql.contains("ORDER BY seq LIMIT 500"));
    }
}
