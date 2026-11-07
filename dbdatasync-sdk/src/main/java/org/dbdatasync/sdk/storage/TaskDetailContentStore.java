/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.storage;

/**
 * task_detail 明细超大内容外置存储点（SPI）：当明细 {@code DATA} 内容超过
 * H2/MySQL 上限（默认 65535 字节）时，写入链路优先经本存储分块外置并返回
 * 引用串（{@code DBDS-REF:...}），取代非破坏的截断——既保住字段上限约束，
 * 又能完整保留超限载荷。
 *
 * <p>设计约束：</p>
 * <ul>
 *     <li>SDK 层只定义接口与静态默认（{@link #current()}），默认无实现时
 *         写入链路保持既有截断行为，零变化；</li>
 *     <li>实现类由上层（biz）在 Spring 容器初始化期调用
 *         {@link #install(TaskDetailContentStore)} 注入，读取端
 *         {@link #current()} 同步取用；</li>
 *     <li>{@link #resolve} 需能反解本实现返回的全部引用串；无法反解的引用
 *         应原样返回（调用方按未解析文本处理），不得抛异常。</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public interface TaskDetailContentStore {

    /** 默认 65535 字节内联阈值（与 {@code DBDataSync_task_detail.DATA} 上限一致）。 */
    int DEFAULT_INLINE_MAX_BYTES = 65535;

    /** 引用串前缀。 */
    String REF_PREFIX = "DBDS-REF:";

    /**
     * 静态当前实现（写读链路共用）。默认 null（既存行为：截断）。
     */
    final class Holder {
        private static volatile TaskDetailContentStore INSTANCE;

        private Holder() {
        }
    }

    static TaskDetailContentStore current() {
        return Holder.INSTANCE;
    }

    static void install(TaskDetailContentStore store) {
        Holder.INSTANCE = store;
    }

    static void uninstall() {
        Holder.INSTANCE = null;
    }

    /**
     * 存储明细超大内容：阈值内原样返回；超限则分块外置并返回引用串。
     *
     * @param content 明细载荷（JSON 文本）；null 视为空串
     * @return inline 原文（阈值内）或 {@code DBDS-REF:<refId>} 引用串
     */
    String store(String content);

    /**
     * 反解引用串为原文；非引用串或无法解析时原样返回。
     *
     * @param stored 存储值（inline 原文或引用串）
     * @return 原文
     */
    String resolve(String stored);
}
