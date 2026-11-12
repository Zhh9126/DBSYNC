package org.dbdatasync.biz.storage;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的 {@link ChunkStore} 实现，使用 {@link ConcurrentHashMap} 保存分块。
 * <p>
 * 内部键为 "refId#index"，适用于测试与小规模场景；生产可替换为文件或数据库实现。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class InMemoryChunkStore implements ChunkStore {

    /** 分块存储表，键为 refId#index。 */
    private final Map<String, String> chunks = new ConcurrentHashMap<String, String>();

    /** 构造内存分块存储。 */
    public InMemoryChunkStore() {
    }

    @Override
    public void save(String refId, int index, String payload) {
        chunks.put(key(refId, index), payload);
    }

    @Override
    public String load(String refId, int index) {
        return chunks.get(key(refId, index));
    }

    @Override
    public void delete(String refId) {
        Iterator<Map.Entry<String, String>> it = chunks.entrySet().iterator();
        String prefix = refId + "#";
        while (it.hasNext()) {
            if (it.next().getKey().startsWith(prefix)) {
                it.remove();
            }
        }
    }

    @Override
    public boolean exists(String refId) {
        String prefix = refId + "#";
        for (String key : chunks.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造内部存储键。
     *
     * @param refId 引用标识
     * @param index 块序号
     * @return 形如 "refId#index" 的键
     */
    private static String key(String refId, int index) {
        return refId + "#" + index;
    }
}
