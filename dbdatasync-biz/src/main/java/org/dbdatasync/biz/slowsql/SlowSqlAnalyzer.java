package org.dbdatasync.biz.slowsql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 慢 SQL 静态分析器：基于记录列表生成统计报告。
 *
 * <p>纯离线计算，无 JDBC / 网络依赖。统计口径：
 * <ul>
 *   <li>{@code totalCount}：参与统计的记录数</li>
 *   <li>{@code slowCount}：{@code executeMillis >= thresholdMillis} 的条数</li>
 *   <li>{@code avgMillis}：平均耗时（整除截断）</li>
 *   <li>{@code maxMillis}：最大耗时</li>
 *   <li>{@code p95Millis}：排序后索引法，索引 = {@code ceil(0.95 * n) - 1}（n &gt; 0）</li>
 *   <li>{@code topSlowest}：按耗时降序前 N 条</li>
 * </ul>
 * 空列表返回全 0 报告，不抛异常。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public final class SlowSqlAnalyzer {

    private SlowSqlAnalyzer() {
    }

    /**
     * 分析慢 SQL 记录列表。
     *
     * @param records         记录列表，可为 null 或空
     * @param thresholdMillis 慢 SQL 阈值（毫秒）
     * @param topN            最慢 Top N 条数（&le;0 时返回空列表）
     * @return 分析报告，永不为 null
     */
    public static SlowSqlReport analyze(List<SlowSqlRecord> records,
                                        long thresholdMillis, int topN) {
        SlowSqlReport report = new SlowSqlReport();
        List<SlowSqlRecord> source =
                (records == null) ? Collections.<SlowSqlRecord>emptyList() : records;
        int n = source.size();
        report.setTotalCount(n);
        if (n == 0) {
            report.setTopSlowest(new ArrayList<SlowSqlRecord>(0));
            return report;
        }

        List<SlowSqlRecord> sorted = new ArrayList<SlowSqlRecord>(source);
        Collections.sort(sorted, new Comparator<SlowSqlRecord>() {
            @Override
            public int compare(SlowSqlRecord a, SlowSqlRecord b) {
                return Long.compare(a.getExecuteMillis(), b.getExecuteMillis());
            }
        });

        // 慢 SQL 条数
        int slowCount = 0;
        long totalMillis = 0L;
        long maxMillis = Long.MIN_VALUE;
        for (SlowSqlRecord record : sorted) {
            long millis = record.getExecuteMillis();
            if (millis >= thresholdMillis) {
                slowCount++;
            }
            totalMillis += millis;
            if (millis > maxMillis) {
                maxMillis = millis;
            }
        }
        report.setSlowCount(slowCount);
        report.setAvgMillis(totalMillis / n);
        report.setMaxMillis(maxMillis);

        // P95：升序排序后索引法，索引 = ceil(0.95 * n) - 1
        int p95Index = (int) Math.ceil(0.95D * n) - 1;
        if (p95Index < 0) {
            p95Index = 0;
        } else if (p95Index >= n) {
            p95Index = n - 1;
        }
        report.setP95Millis(sorted.get(p95Index).getExecuteMillis());

        // Top N：升序序列尾部取 topN 条后倒序，得到耗时降序
        int limit = topN;
        if (limit > n) {
            limit = n;
        }
        if (limit <= 0) {
            report.setTopSlowest(new ArrayList<SlowSqlRecord>(0));
        } else {
            List<SlowSqlRecord> top = new ArrayList<SlowSqlRecord>(limit);
            for (int i = n - 1; i >= n - limit; i--) {
                top.add(sorted.get(i));
            }
            report.setTopSlowest(top);
        }
        return report;
    }
}
