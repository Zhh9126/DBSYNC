/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlite.cdc;

import org.dbdatasync.connector.sqlite.cdc.internal.SQLiteTriggerCdcDialect;
import org.dbdatasync.sdk.cdc.trigger.AbstractTriggerLogListener;
import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;

import java.util.Map;

/**
 * Wave24：Trigger CDC（SQLite 实现）。
 *
 * <p>Wave26 起通用引擎上移到 {@link AbstractTriggerLogListener}（位点捕获、幂等建表建触发器、
 * seq 轮询还原、消费清理），本类只提供 SQLite 方言：AUTOINCREMENT 影子表、
 * SQL 体触发器（逐列 quote 拼接，不依赖 JSON1 扩展）。行为与 Wave24 完全兼容。</p>
 *
 * @author DBDataSync
 * @version 2.1.6
 * @date 2026-11-09
 */
public final class TriggerLogListener extends AbstractTriggerLogListener {

    public static final String LOG_TABLE = AbstractTriggerLogListener.LOG_TABLE;
    public static final String SEQ_KEY = AbstractTriggerLogListener.SEQ_KEY;

    private final TriggerCdcDialect dialect = new SQLiteTriggerCdcDialect();

    @Override
    protected TriggerCdcDialect dialect() {
        return dialect;
    }

    /** 解析 {@code col='v';col2=NULL} 形式的逐列文本（兼容 Wave24 测试入口） */
    public static Map<String, String> parsePayload(String payload) {
        return AbstractTriggerLogListener.parsePayload(payload);
    }

    public static String unquote(String raw) {
        return AbstractTriggerLogListener.unquote(raw);
    }
}
