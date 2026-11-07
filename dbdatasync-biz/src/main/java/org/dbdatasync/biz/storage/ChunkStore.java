package org.dbdatasync.biz.storage;

/**
 * 分块存储接口：为超大内容（单条内容超过 65535 字节上限）提供外置分块落存能力。
 * <p>
 * 原存储字段只保存引用串（形如 DBDS-REF:&lt;refId&gt;），真实负载按块存入本接口的实现中。
 * 本接口为「非破坏式扩容路径」的一部分，不修改任何既有表结构或既有类。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public interface ChunkStore {

    /**
     * 保存一个分块。
     *
     * @param refId   引用标识，同一 refId 的所有块属于同一段原文
     * @param index   块序号，从 0 开始
     * @param payload 本块真实负载（不含协议头）
     */
    void save(String refId, int index, String payload);

    /**
     * 加载指定引用的某个分块。
     *
     * @param refId 引用标识
     * @param index 块序号，从 0 开始
     * @return 块负载；不存在时返回 null
     */
    String load(String refId, int index);

    /**
     * 删除指定引用下的全部分块。
     *
     * @param refId 引用标识
     */
    void delete(String refId);

    /**
     * 判断指定引用下是否仍存在任一分块。
     *
     * @param refId 引用标识
     * @return 存在任一分块返回 true，否则返回 false
     */
    boolean exists(String refId);
}
