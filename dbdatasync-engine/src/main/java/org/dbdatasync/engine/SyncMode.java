/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.engine;

/**
 * 同步模式。
 *
 * <ul>
 *     <li>{@link #SYNC}：reader 与 writer 在同一 Job 线程内经 Channel 直连，端到端一致，失败即回滚，
 *         适用于强一致要求的同事务场景。</li>
 *     <li>{@link #ASYNC}：reader 将记录写入 Channel 后由独立的 WriterRunner 线程消费，
 *         两者解耦，reader 可先行批量抽取并配合本地 WAL/中间缓冲，writer 异步落库并 ACK，
 *         支持更高的吞吐与故障隔离，适用于大数据量/弱一致场景。</li>
 * </ul>
 */
public enum SyncMode {
    SYNC,
    ASYNC
}
