/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.dameng.cdc;

import org.dbdatasync.connector.dameng.cdc.internal.DamengTriggerCdcDialect;
import org.dbdatasync.sdk.cdc.trigger.AbstractTriggerLogListener;
import org.dbdatasync.sdk.cdc.trigger.TriggerCdcDialect;

/**
 * Wave26：Trigger CDC（达梦实现，SQL 体触发器方言）。
 *
 * <p>与 LogMiner 通道（{@code DamengListener}）互补：LogMiner 需要归档模式与补充日志，
 * Trigger CDC 无需归档、在源表上挂 AFTER 触发器捕获行变更。两者互斥使用，按任务选择其一。</p>
 *
 * <p><b>诚实边界</b>：SQL 生成形状有离线单测校验，未连接真实达梦执行；首次启用请在
 * 测试库执行 {@link #installSchema()} / {@link #installTriggers()} 验证方言兼容性
 * （DM 8.1.2+ 支持 CREATE TABLE IF NOT EXISTS）。</p>
 *
 * @author DBDataSync
 * @version 2.1.6
 * @date 2026-11-09
 */
public class DamengTriggerLogListener extends AbstractTriggerLogListener {

    public static final String LOG_TABLE = AbstractTriggerLogListener.LOG_TABLE;
    public static final String SEQ_KEY = AbstractTriggerLogListener.SEQ_KEY;

    private final TriggerCdcDialect dialect = new DamengTriggerCdcDialect();

    @Override
    protected TriggerCdcDialect dialect() {
        return dialect;
    }
}
