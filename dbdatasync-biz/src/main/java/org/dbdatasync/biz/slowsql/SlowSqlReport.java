package org.dbdatasync.biz.slowsql;

import java.util.Collections;
import java.util.List;

/**
 * 慢 SQL 分析报告：总量 / 慢量 / 耗时统计 / 最慢 Top N。
 *
 * <p>由 {@link SlowSqlAnalyzer#analyze(List, long, int)} 生成，
 * {@code topSlowest} 为只读视图，按 {@code executeMillis} 降序。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class SlowSqlReport {

    /** 参与统计的记录总数 */
    private int totalCount;

    /** 超过阈值的慢 SQL 条数 */
    private int slowCount;

    /** 平均耗时（毫秒） */
    private long avgMillis;

    /** 最大耗时（毫秒） */
    private long maxMillis;

    /** P95 耗时（毫秒） */
    private long p95Millis;

    /** 最慢 Top N（按 executeMillis 降序），只读视图 */
    private List<SlowSqlRecord> topSlowest = Collections.emptyList();

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getSlowCount() {
        return slowCount;
    }

    public void setSlowCount(int slowCount) {
        this.slowCount = slowCount;
    }

    public long getAvgMillis() {
        return avgMillis;
    }

    public void setAvgMillis(long avgMillis) {
        this.avgMillis = avgMillis;
    }

    public long getMaxMillis() {
        return maxMillis;
    }

    public void setMaxMillis(long maxMillis) {
        this.maxMillis = maxMillis;
    }

    public long getP95Millis() {
        return p95Millis;
    }

    public void setP95Millis(long p95Millis) {
        this.p95Millis = p95Millis;
    }

    public List<SlowSqlRecord> getTopSlowest() {
        return topSlowest;
    }

    public void setTopSlowest(List<SlowSqlRecord> topSlowest) {
        if (topSlowest == null) {
            this.topSlowest = Collections.emptyList();
        } else {
            this.topSlowest = Collections.unmodifiableList(topSlowest);
        }
    }
}
