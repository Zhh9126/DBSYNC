package org.dbdatasync.biz.cutover;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影子写比对缓冲区。
 * <p>
 * 在影子写阶段，调用方将同一条业务数据在源端与目标端的写入结果
 * （通常为序列化后的 JSON 文本）成对登记到本缓冲区，按
 * 「表名|主键」作为唯一键保序存储（{@link LinkedHashMap}）。
 * 通过 {@link #mismatchCount()} 统计两端不一致的对数，
 * 通过 {@link #flipSafe(int)} 判断是否满足安全切流条件
 * （样本量达到阈值且零失配）。本类只做内存比对，不做任何 I/O。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class ShadowWriteBuffer {

    /** 配对数据：key = table|pk，value = [sourceJson, shadowJson]。 */
    private final Map<String, String[]> pairs = new LinkedHashMap<String, String[]>();

    /**
     * 登记一对影子写比对记录。
     * <p>
     * 同一 key（table|pk）重复登记时按最新一次写入覆盖，
     * 以保证比对结果反映最新数据状态。
     * </p>
     *
     * @param table      表名，不允许为空
     * @param pk         主键值，不允许为空
     * @param sourceJson 源端写入结果（序列化文本）
     * @param shadowJson 目标端影子写结果（序列化文本）
     */
    public synchronized void recordPair(String table, String pk, String sourceJson, String shadowJson) {
        if (table == null || table.length() == 0) {
            throw new IllegalArgumentException("表名不允许为空");
        }
        if (pk == null || pk.length() == 0) {
            throw new IllegalArgumentException("主键不允许为空");
        }
        pairs.put(table + "|" + pk, new String[]{sourceJson, shadowJson});
    }

    /**
     * 统计源端与目标端不一致的配对数。
     * <p>
     * 两端文本均为 null 视为一致，仅一端为 null 或文本内容不等视为失配。
     * </p>
     *
     * @return 失配配对数
     */
    public synchronized int mismatchCount() {
        int mismatch = 0;
        for (String[] pair : pairs.values()) {
            if (!isSame(pair[0], pair[1])) {
                mismatch++;
            }
        }
        return mismatch;
    }

    /**
     * 获取当前已登记的配对总数。
     *
     * @return 配对总数
     */
    public synchronized int pairedCount() {
        return pairs.size();
    }

    /**
     * 判断当前是否满足安全切流条件：配对样本数不小于 minSamples
     * 且失配数为 0。
     *
     * @param minSamples 最小样本量阈值
     * @return 满足条件返回 true，否则返回 false
     */
    public synchronized boolean flipSafe(int minSamples) {
        if (minSamples <= 0) {
            throw new IllegalArgumentException("最小样本量阈值必须大于 0");
        }
        return pairs.size() >= minSamples && mismatchCount() == 0;
    }

    /**
     * 清空全部配对数据（通常在回滚或重新开始影子写时调用）。
     */
    public synchronized void clear() {
        pairs.clear();
    }

    /**
     * 判断两端文本是否一致（null 安全）。
     *
     * @param a 源端文本
     * @param b 目标端文本
     * @return 一致返回 true
     */
    private static boolean isSame(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }
}
