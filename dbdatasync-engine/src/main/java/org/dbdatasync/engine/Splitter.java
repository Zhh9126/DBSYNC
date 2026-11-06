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

    /**
     * 把 [min, max] 值域切分为 count 个左闭右开区间 [start, end)。
     * <p>与 {@link #splitNumericRange} 的闭区间不同，此处 end 为开区间（不含），
     * 便于拼接 {@code splitKey >= ? AND splitKey < ?} 这样无重叠、无遗漏的分片条件：
     * 最后一个分片的 end 取 {@code max + 1}，保证能覆盖到最大值本身。</p>
     *
     * @param min   最小值（含）
     * @param max   最大值（含）
     * @param count 分片数
     * @return 区间数组 [{start,end}, ...]，end 为开区间；count &lt;= 0 或 max &lt; min 时返回空数组
     */
    public static long[][] splitHalfOpenRange(long min, long max, int count) {
        if (max < min || count <= 0) {
            return new long[0][];
        }
        int n = Math.max(1, count);
        long total = max - min + 1;
        // 每片至少 1 条，避免 step=0 死循环
        long step = Math.max(1, total / n);
        long[][] ranges = new long[n][];
        long start = min;
        for (int i = 0; i < n; i++) {
            long end;
            if (i == n - 1) {
                // 最后一片覆盖到 max，end 取 max+1（开区间）
                end = max == Long.MAX_VALUE ? max : max + 1;
            } else {
                end = start + step;
                if (end > max) {
                    end = max;
                }
                if (end <= start) {
                    end = start + 1;
                }
            }
            ranges[i] = new long[]{start, end};
            start = end;
            if (start > max || (start == max && i == n - 1)) {
                if (i == n - 1) {
                    // 已覆盖全部（最后一片），无需收缩
                } else {
                    // 提前覆盖完：收缩数组，丢弃无意义的空区间
                    long[][] shrunk = new long[i + 1][];
                    System.arraycopy(ranges, 0, shrunk, 0, i + 1);
                    return shrunk;
                }
            }
        }
        return ranges;
    }
}
