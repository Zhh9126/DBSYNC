/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.mysql.cdc.trigger;

import org.dbdatasync.connector.mysql.cdc.trigger.internal.MySQLTriggerCdcDialect;
import org.dbdatasync.sdk.cdc.trigger.AbstractTriggerLogListener;
import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;

/**
 * Wave28：MySQL Trigger CDC（通用引擎 {@link AbstractTriggerLogListener} 的 MySQL 方言接入）。
 *
 * <p>免 binlog 兜底捕获路径：源表 AFTER 触发器把变更写入影子日志表
 * {@code __DBSYNC_CDC_LOG}，按单调 seq 轮询还原行事件。与 binlog 通道互斥使用
 * （任务监听方式选 {@code trigger} 时生效）；影子表与触发器幂等创建，
 * 重启自动续读位点。首次在真实 MySQL 上启用前请先在测试库验证 DDL。</p>
 *
 * @author DBDataSync
 * @version 2.1.7
 * @date 2026-11-10
 */
public class MySQLTriggerLogListener extends AbstractTriggerLogListener {

    public static final String LOG_TABLE = AbstractTriggerLogListener.LOG_TABLE;
    public static final String SEQ_KEY = AbstractTriggerLogListener.SEQ_KEY;

    private final TriggerCdcDialect dialect = new MySQLTriggerCdcDialect();

    @Override
    protected TriggerCdcDialect dialect() {
        return dialect;
    }
}
