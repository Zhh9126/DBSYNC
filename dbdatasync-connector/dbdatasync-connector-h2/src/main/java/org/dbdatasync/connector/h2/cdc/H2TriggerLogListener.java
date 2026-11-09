/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.h2.cdc;

import org.dbdatasync.connector.h2.cdc.internal.H2TriggerCdcDialect;
import org.dbdatasync.sdk.cdc.trigger.AbstractTriggerLogListener;
import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;

/**
 * Wave26：Trigger CDC（H2 实现，通用引擎 {@link AbstractTriggerLogListener} 的内联源码方言接入）。
 *
 * <p>对没有逻辑复制日志的 H2 源库（如把 H2 当作轻量业务库的部署），用行级触发器把变更
 * 写入影子日志表 {@code __DBSYNC_CDC_LOG}，按单调 seq 轮询还原行事件。触发器 DDL 由
 * {@link H2TriggerCdcDialect} 以内联 Java 源码生成（上下文烘焙进源码，数据库重开重新
 * 实例化依然正确），影子表 seq 用 IDENTITY 自增。</p>
 *
 * @author DBDataSync
 * @version 2.1.6
 * @date 2026-11-09
 */
public class H2TriggerLogListener extends AbstractTriggerLogListener {

    public static final String LOG_TABLE = AbstractTriggerLogListener.LOG_TABLE;
    public static final String SEQ_KEY = AbstractTriggerLogListener.SEQ_KEY;

    private final TriggerCdcDialect dialect = new H2TriggerCdcDialect();

    @Override
    protected TriggerCdcDialect dialect() {
        return dialect;
    }
}
