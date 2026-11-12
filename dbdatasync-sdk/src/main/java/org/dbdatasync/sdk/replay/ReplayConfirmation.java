package org.dbdatasync.sdk.replay;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 重放结果确认表：把「异步写入管道」与「失败明细删除」之间的语义缺口补上。
 *
 * <p>背景：旧 {@code DataSyncServiceImpl.sync()} 把事件投递到 bufferActuatorRouter 后
 * 无条件删除失败明细、并把 Meta.fail 自减，写入其实尚未发生。一旦写入再次失败，
 * 这条数据就永久丢失且统计失真。</p>
 *
 * <p>新流程：重放前 {@link #begin}，写入链路拿到 {@code ChangedOffset.replayId}
 * 并在 {@code FlushStrategyImpl} 落结果时 {@link #mark}，重放方 {@link #awaitSuccess}
 * 只有收到明确成功才删除记录并回写统计。超时/失败一律保留记录，交给下一轮重放。</p>
 */
public final class ReplayConfirmation {

    private static final long DEFAULT_TIMEOUT_MILLIS = 3000L;

    private static final class Slot {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean success;
    }

    private static final ConcurrentHashMap<String, Slot> PENDING = new ConcurrentHashMap<String, Slot>();

    private ReplayConfirmation() {
    }

    public static void begin(String metaId, String replayId) {
        if (metaId == null || replayId == null) {
            return;
        }
        PENDING.put(key(metaId, replayId), new Slot());
    }

    /** 由写入完成点调用；success=true 仅当该批 failData 为空 */
    public static void mark(String metaId, String replayId, boolean success) {
        if (metaId == null || replayId == null) {
            return;
        }
        Slot slot = PENDING.get(key(metaId, replayId));
        if (slot != null) {
            slot.success = success;
            slot.latch.countDown();
        }
    }

    /** 等待写入结果；超时或异常一律视为未确认（保留明细，不丢数据） */
    public static boolean awaitSuccess(String metaId, String replayId) {
        return awaitSuccess(metaId, replayId, DEFAULT_TIMEOUT_MILLIS);
    }

    public static boolean awaitSuccess(String metaId, String replayId, long timeoutMillis) {
        if (metaId == null || replayId == null) {
            return false;
        }
        Slot slot = PENDING.get(key(metaId, replayId));
        if (slot == null) {
            return false;
        }
        try {
            slot.latch.await(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return slot.success;
    }

    public static void end(String metaId, String replayId) {
        if (metaId != null && replayId != null) {
            PENDING.remove(key(metaId, replayId));
        }
    }

    public static int pending() {
        return PENDING.size();
    }

    /** 仅供单测 */
    public static void clear() {
        PENDING.clear();
    }

    private static String key(String metaId, String replayId) {
        return metaId + "|" + replayId;
    }
}
