/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.cdc.trigger;

import org.dbdatasync.sdk.model.Field;

import java.util.List;

/**
 * Wave26：Trigger CDC 方言抽象。
 *
 * <p>Trigger CDC 的通用引擎在 {@link AbstractTriggerLogListener}（影子日志表 +
 * seq 单调轮询 + 位点推进 + 消费清理），各数据库只有「影子表建表 DDL」与
 * 「行级触发器 DDL」两处方言差异。实现方按能力分两类：</p>
 * <ul>
 *     <li><b>SQL 体触发器</b>（SQLite、DM 等）：{@link #javaBodyTriggers()} 返回 false，
 *         触发器 SQL 体内直接拼接 payload/ts 表达式；</li>
 *     <li><b>Java 体触发器</b>（H2 等 SQL 触发器必须 CALL Java 类的库）：
 *         返回 true，payload/ts 由触发器类在 {@code fire()} 里用 Java 拼装，
 *         DDL 只负责挂接 {@link #javaTriggerClass()}。</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 2.1.6
 * @date 2026-11-09
 */
public interface TriggerCdcDialect {

    /** 方言名（sqlite / h2 / dameng ...），用于触发器名与日志 */
    String name();

    /** 幂等建影子日志表 DDL（seq 自增主键 + tbl/op/pk/payload/ts） */
    String createLogTableSql(String logTable);

    /**
     * 生成一张表一类操作的行级触发器 DDL（可能多条）。
     *
     * @param logTable  影子日志表名
     * @param tableName 业务表名
     * @param operation INSERT / UPDATE / DELETE（大写）
     * @param columns   业务表字段（首个视为主键列）
     */
    List<String> createTriggerSqls(String logTable, String tableName, String operation, List<Field> columns);

    /** true = 触发器体为 Java 类，payload/ts 在 fire() 中拼装 */
    boolean javaBodyTriggers();

    /** Java 体触发器类全名（非 Java 体方言返回 null） */
    default String javaTriggerClass() {
        return null;
    }

    /**
     * Wave28：按 seq 增量轮询一批日志的 SQL（占位符 {@code ?} 为上次位点）。
     * 默认 LIMIT 语法（SQLite/H2/MySQL/PG）；Oracle 用 FETCH FIRST、SQL Server 用 TOP 覆写。
     */
    default String pollPageSql(String logTable, int batchSize) {
        return "SELECT seq,tbl,op,pk,payload,ts FROM " + logTable + " WHERE seq > ? ORDER BY seq LIMIT " + batchSize;
    }

    /** 三类 DML 操作常量 */
    String[] OPERATIONS = {"INSERT", "UPDATE", "DELETE"};

    /** 触发器名后缀 */
    static String triggerSuffix(String tableName, String operation) {
        return "__dbsync_cdc_" + operation.toLowerCase(java.util.Locale.ROOT) + "_"
                + tableName.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
