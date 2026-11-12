/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.postgresql.cdc.trigger;

import org.dbdatasync.connector.postgresql.cdc.trigger.internal.PostgreSQLTriggerCdcDialect;
import org.dbdatasync.sdk.cdc.trigger.AbstractTriggerLogListener;
import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;

/**
 * Wave28：PostgreSQL Trigger CDC（通用引擎 {@link AbstractTriggerLogListener} 的 PG 方言接入）。
 *
 * <p>免逻辑复制兜底捕获路径：源表 AFTER 触发器（PL/pgSQL 函数体）把变更写入
 * 影子日志表 {@code __DBSYNC_CDC_LOG}，按单调 seq 轮询还原行事件。与逻辑复制
 * 通道互斥使用（任务监听方式选 {@code trigger} 时生效）；人大金仓 PG 兼容模式
 * 下可按同一语法启用。首次在真实库上启用前请先在测试库验证 DDL。</p>
 *
 * @author DBDataSync
 * @version 2.1.7
 * @date 2026-11-10
 */
public class PostgreSQLTriggerLogListener extends AbstractTriggerLogListener {

    public static final String LOG_TABLE = AbstractTriggerLogListener.LOG_TABLE;
    public static final String SEQ_KEY = AbstractTriggerLogListener.SEQ_KEY;

    private final TriggerCdcDialect dialect = new PostgreSQLTriggerCdcDialect();

    @Override
    protected TriggerCdcDialect dialect() {
        return dialect;
    }
}
