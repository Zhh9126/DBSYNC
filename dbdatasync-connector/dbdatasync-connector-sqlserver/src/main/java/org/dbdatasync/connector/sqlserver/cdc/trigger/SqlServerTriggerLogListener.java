/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlserver.cdc.trigger;

import org.dbdatasync.connector.sqlserver.cdc.trigger.internal.SqlServerTriggerCdcDialect;
import org.dbdatasync.sdk.cdc.trigger.AbstractTriggerLogListener;
import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;

/**
 * Wave28：SQL Server Trigger CDC（通用引擎 {@link AbstractTriggerLogListener} 的 SQL Server 方言接入）。
 *
 * <p>免 CDC 组件兜底捕获路径：语句级触发器把变更写入影子日志表
 * {@code __DBSYNC_CDC_LOG}（inserted/deleted 虚表一次写整批），按单调 seq 轮询
 * 还原行事件。与 LSN/CDC 通道互斥使用（任务监听方式选 {@code trigger} 时生效）。
 * 需 SQL Server 2016+（DATEDIFF_BIG）；首次在真实库上启用前请先在测试库验证 DDL。</p>
 *
 * @author DBDataSync
 * @version 2.1.7
 * @date 2026-11-10
 */
public class SqlServerTriggerLogListener extends AbstractTriggerLogListener {

    public static final String LOG_TABLE = AbstractTriggerLogListener.LOG_TABLE;
    public static final String SEQ_KEY = AbstractTriggerLogListener.SEQ_KEY;

    private final TriggerCdcDialect dialect = new SqlServerTriggerCdcDialect();

    @Override
    protected TriggerCdcDialect dialect() {
        return dialect;
    }
}
