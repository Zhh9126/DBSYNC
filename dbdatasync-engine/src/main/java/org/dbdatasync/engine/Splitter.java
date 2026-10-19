/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.engine;

/**
 * 分治切分器（DataX 式 Job→Task 拆分）。
 *
 * <p>根据分片主键（splitPk）与采样得到的 [min, max] 范围，将整表均匀切分为 {@code concurrency} 个
 * 连续的左闭右开子区间，每个子区间对应一个独立的 Task（由 {@code TableGroup} 描述）。
 * 各 Task 之间无重叠、可并行执行，从而实现水平扩展。</p>
 *
 * <p>这是本项目相对单线程全表扫描的核心创新点：将大表拆分为可并行、可断点续传的若干子任务，
 * 既提升吞吐，又使故障恢复粒度从"整表"下降到"单区间"。</p>
 */
public final class Splitter {

    private Splitter() {
    }

    /**
     * 按数值范围切分。
     *
     * @param splitPk    分片主键
     * @param min        最小值（含）
     * @param max        最大值（含）
     * @param concurrency 并发数
     * @return 切分后的区间数组（每个为 [start, end]）
     */
    public static long[][] splitNumericRange(String splitPk, long min, long max, int concurrency) {
        if (max < min) {
            return new long[0][];
        }
        int n = Math.max(1, concurrency);
        long total = max - min + 1;
        long step = Math.max(1, total / n);
        long[][] ranges = new long[n][];
        long start = min;
        for (int i = 0; i < n; i++) {
            long end = (i == n - 1) ? max : Math.min(max, start + step - 1);
            ranges[i] = new long[]{start, end};
            start = end + 1;
            if (start > max) {
                // 收缩数组
                long[][] shrunk = new long[i + 1][];
                System.arraycopy(ranges, 0, shrunk, 0, i + 1);
                return shrunk;
            }
        }
        return ranges;
    }
}
