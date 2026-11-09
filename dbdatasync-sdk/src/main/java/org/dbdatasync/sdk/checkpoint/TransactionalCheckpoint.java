package org.dbdatasync.sdk.checkpoint;

/**
 * 事务型 checkpoint 协调器：把「位点提交」与「幂等键确认」绑定在一次提交里。
 *
 * <p>提交顺序：数据写入成功 → {@link #commit(CheckpointRecord)} 原子写位点文件 →
 * 追加幂等键账本。若进程在两步之间崩溃，重启后 {@link #isDuplicate} 会从
 * checkpoint 文件里读到 lastKey 并自动补记账本，避免同一变更被重复应用。</p>
 *
 * <p>这是向 exactly-once 收敛的工程实现，不宣称强一致 exactly-once：
 * 目标端写入与位点文件不在同一个数据库事务里，跨存储原子性仍需
 * 目标端幂等约束（唯一键 / 主键 upsert）配合。</p>
 */
public final class TransactionalCheckpoint {

    private final CheckpointStore store;
    private final IdempotencyLedger ledger;

    public TransactionalCheckpoint(CheckpointStore store, IdempotencyLedger ledger) {
        if (store == null || ledger == null) {
            throw new IllegalArgumentException("store and ledger are required");
        }
        this.store = store;
        this.ledger = ledger;
    }

    /**
     * 判断一条变更是否已经确认过。
     * 崩溃恢复：账本缺失但 checkpoint.lastKey 命中时自动补记并返回 true。
     */
    public boolean isDuplicate(String metaId, String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (ledger.isDuplicate(key)) {
            return true;
        }
        CheckpointRecord last = store.load(metaId);
        if (last != null && key.equals(last.getIdempotencyKey())) {
            ledger.mark(key);
            return true;
        }
        return false;
    }

    /** 数据写入成功后提交位点与幂等键 */
    public void commit(CheckpointRecord record) {
        if (record == null || record.getMetaId() == null) {
            return;
        }
        if (record.getCommittedAt() <= 0) {
            record.setCommittedAt(System.currentTimeMillis());
        }
        store.save(record);
        ledger.mark(record.getIdempotencyKey());
    }

    /** 启动时读取最后一次确认的位点 */
    public CheckpointRecord recover(String metaId) {
        return store.load(metaId);
    }

    public void remove(String metaId) {
        store.remove(metaId);
    }

    public CheckpointStore getStore() {
        return store;
    }

    public IdempotencyLedger getLedger() {
        return ledger;
    }
}
