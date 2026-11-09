package org.dbdatasync.sdk.checkpoint;

/**
 * checkpoint 持久化抽象。
 *
 * <p>本地实现见 {@link FileCheckpointStore}；集群部署可替换为共享存储
 * （数据库表 / 对象存储 / 分布式 KV），业务代码不感知。</p>
 */
public interface CheckpointStore {

    /** 原子保存（覆盖同一 metaId 的旧记录） */
    void save(CheckpointRecord record);

    /** 读取最后一次成功提交的位点；无记录返回 null */
    CheckpointRecord load(String metaId);

    /** 删除某任务的 checkpoint（任务删除时调用） */
    void remove(String metaId);
}
