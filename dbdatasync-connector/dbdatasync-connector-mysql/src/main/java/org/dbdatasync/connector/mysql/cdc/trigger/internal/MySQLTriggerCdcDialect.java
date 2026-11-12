/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.mysql.cdc.trigger.internal;

import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;
import org.dbdatasync.sdk.model.Field;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Wave28：MySQL Trigger CDC 方言（SQL 体触发器，NEW/OLD 别名）。
 *
 * <p>定位：MySQL {@code MySQLListener} 走 binlog 协议（需 ROW 格式 + REPLICATION 权限）；
 * Trigger CDC 是免 binlog 的兜底捕获路径——云托管账号无 REPLICATION SLAVE 权限、
 * binlog 被关闭等场景下，用源表 AFTER 触发器把变更写入影子日志表。</p>
 *
 * <p>实现要点：触发器体为单条 INSERT，JDBC 直发无需客户端 DELIMITER；
 * MySQL 字符串字面量需同时转义反斜杠与单引号；NULL 必须不带引号写入
 * （unquote 还原时区分 null 与字符串 "NULL"）；{@code NOW(3)} 提供毫秒时间戳。</p>
 *
 * <p><b>诚实边界</b>：SQL 生成有离线单测校验形状，本机无 MySQL 实例未真实执行；
 * 首次启用请在测试库验证 installSchema/installTriggers。</p>
 *
 * @author DBDataSync
 * @version 2.1.7
 * @date 2026-11-10
 */
public class MySQLTriggerCdcDialect implements TriggerCdcDialect {

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public String createLogTableSql(String logTable) {
        return "CREATE TABLE IF NOT EXISTS " + logTable + " ("
                + "seq BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tbl VARCHAR(512) NOT NULL, op VARCHAR(16) NOT NULL, pk VARCHAR(512), payload LONGTEXT, ts BIGINT)"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    public List<String> createTriggerSqls(String logTable, String tableName, String operation, List<Field> columns) {
        String suffix = operation.toLowerCase(Locale.ROOT);
        String rowAlias = "DELETE".equalsIgnoreCase(operation) ? "OLD" : "NEW";
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                payload.append(", ';', ");
            }
            payload.append(valueExpr(columns.get(i).getName(), rowAlias));
        }
        String sql = "CREATE TRIGGER " + TriggerCdcDialect.triggerSuffix(tableName, operation)
                + " AFTER " + operation.toUpperCase(Locale.ROOT) + " ON `" + tableName + "` FOR EACH ROW"
                + " INSERT INTO " + logTable + "(tbl,op,pk,payload,ts) VALUES ("
                + "'" + tableName + "', '" + operation.toUpperCase(Locale.ROOT) + "', "
                + "CAST(" + rowAlias + ".`" + columns.get(0).getName() + "` AS CHAR), "
                + "CONCAT(" + payload.toString() + "), "
                + "ROUND(UNIX_TIMESTAMP(NOW(3)) * 1000))";
        return Collections.singletonList(sql);
    }

    /**
     * {@code 'col=', IF(NEW.`col` IS NULL, 'NULL', CONCAT('''', REPLACE(REPLACE(CAST(NEW.`col` AS CHAR),'\\','\\\\'),'''',''''''),''''))}
     *
     * <p>NULL 不带引号写入；字符串值先转义反斜杠再双写单引号。</p>
     */
    public static String valueExpr(String column, String rowAlias) {
        String cast = "CAST(" + rowAlias + ".`" + column + "` AS CHAR)";
        return "'" + column + "=', IF(" + rowAlias + ".`" + column + "` IS NULL, 'NULL', "
                + "CONCAT('''', REPLACE(REPLACE(" + cast + ", '\\\\', '\\\\\\\\'), '''', ''''''), ''''))";
    }

    @Override
    public boolean javaBodyTriggers() {
        return false;
    }
}
