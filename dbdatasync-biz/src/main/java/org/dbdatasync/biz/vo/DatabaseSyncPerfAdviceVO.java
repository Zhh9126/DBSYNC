package org.dbdatasync.biz.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 整库迁移性能适配报告（文档级建议，不自动执行 DDL）。
 *
 * <p>Wave10 Q1 口径：{@code totalRows = successRows + failRows} 恒成立；
 * {@code metaTotal} 为任务级 Meta.total，仅作核对字段；{@code rowsConsistent} 表示二者是否一致。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
public class DatabaseSyncPerfAdviceVO {

    /** 任务 ID */
    private String taskId;

    /** 任务名 */
    private String taskName;

    /** 是否已执行过迁移（无 meta/无数据时 false） */
    private boolean migrated;

    /** 总数据量（成功 + 失败，恒等派生，不直接取 Meta.total） */
    private long totalRows;

    /** 成功行数（任务级 Meta.success） */
    private long successRows;

    /** 失败行数（任务级 Meta.fail） */
    private long failRows;

    /** 任务级 Meta.total（仅作核对字段，不参与报告主数值，不改写 Meta） */
    private long metaTotal;

    /** Meta.total 是否等于 成功+失败（false 时结论与前端显示核对提示） */
    private boolean rowsConsistent;

    /** 总耗时 ms（beginTime→endTime） */
    private long durationMs;

    /** 平均吞吐（行/s，分子只用成功行数） */
    private double throughputPerSecond;

    /** 表总数 */
    private int tableCount;

    /** 大表列表（sourceTotal 降序前 N） */
    private List<BigTableAdvice> bigTables = new ArrayList<>();

    /** 差异表列表（diffTotal>0，建议补迁/重校验） */
    private List<DiffTableAdvice> diffTables = new ArrayList<>();

    /** 参数调优建议（readNum/batchNum/threadNum） */
    private List<String> parameterAdvices = new ArrayList<>();

    /** 目标库参数建议（按方言） */
    private List<String> targetDbAdvices = new ArrayList<>();

    /** 汇总结论 */
    private String conclusion;

    /** 任务配置的每秒行数限速（回显，<=0 归一 0） */
    private long configuredRecordsPerSecond;

    /** 任务配置的每秒字节数限速（回显，<=0 归一 0） */
    private long configuredBytesPerSecond;

    /** 是否启用低峰时间窗（回显） */
    private boolean offPeakWindowEnabled;

    /** 低峰时间窗文案（如「22:00 ~ 06:00」/「未启用」/「未配置」） */
    private String offPeakWindowText;

    /** 每千行平均耗时 ms（由 durationMs/successRows 派生，保留 1 位小数） */
    private double avgMsPerThousandRows;

    /** 按配置行限速估算的理论最短耗时 ms（由 totalRows/recordsPerSecond 派生） */
    private long estimatedMinDurationMs;

    /** 成本/耗时/吞吐统计一句中文摘要（纯派生展示，不改 Wave10 口径） */
    private String costSummary;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public boolean isMigrated() {
        return migrated;
    }

    public void setMigrated(boolean migrated) {
        this.migrated = migrated;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(long totalRows) {
        this.totalRows = totalRows;
    }

    public long getSuccessRows() {
        return successRows;
    }

    public void setSuccessRows(long successRows) {
        this.successRows = successRows;
    }

    public long getFailRows() {
        return failRows;
    }

    public void setFailRows(long failRows) {
        this.failRows = failRows;
    }

    public long getMetaTotal() {
        return metaTotal;
    }

    public void setMetaTotal(long metaTotal) {
        this.metaTotal = metaTotal;
    }

    public boolean isRowsConsistent() {
        return rowsConsistent;
    }

    public void setRowsConsistent(boolean rowsConsistent) {
        this.rowsConsistent = rowsConsistent;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public double getThroughputPerSecond() {
        return throughputPerSecond;
    }

    public void setThroughputPerSecond(double throughputPerSecond) {
        this.throughputPerSecond = throughputPerSecond;
    }

    public int getTableCount() {
        return tableCount;
    }

    public void setTableCount(int tableCount) {
        this.tableCount = tableCount;
    }

    public List<BigTableAdvice> getBigTables() {
        return bigTables;
    }

    public void setBigTables(List<BigTableAdvice> bigTables) {
        this.bigTables = bigTables;
    }

    public List<DiffTableAdvice> getDiffTables() {
        return diffTables;
    }

    public void setDiffTables(List<DiffTableAdvice> diffTables) {
        this.diffTables = diffTables;
    }

    public List<String> getParameterAdvices() {
        return parameterAdvices;
    }

    public void setParameterAdvices(List<String> parameterAdvices) {
        this.parameterAdvices = parameterAdvices;
    }

    public List<String> getTargetDbAdvices() {
        return targetDbAdvices;
    }

    public void setTargetDbAdvices(List<String> targetDbAdvices) {
        this.targetDbAdvices = targetDbAdvices;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public long getConfiguredRecordsPerSecond() {
        return configuredRecordsPerSecond;
    }

    public void setConfiguredRecordsPerSecond(long configuredRecordsPerSecond) {
        this.configuredRecordsPerSecond = configuredRecordsPerSecond;
    }

    public long getConfiguredBytesPerSecond() {
        return configuredBytesPerSecond;
    }

    public void setConfiguredBytesPerSecond(long configuredBytesPerSecond) {
        this.configuredBytesPerSecond = configuredBytesPerSecond;
    }

    public boolean isOffPeakWindowEnabled() {
        return offPeakWindowEnabled;
    }

    public void setOffPeakWindowEnabled(boolean offPeakWindowEnabled) {
        this.offPeakWindowEnabled = offPeakWindowEnabled;
    }

    public String getOffPeakWindowText() {
        return offPeakWindowText;
    }

    public void setOffPeakWindowText(String offPeakWindowText) {
        this.offPeakWindowText = offPeakWindowText;
    }

    public double getAvgMsPerThousandRows() {
        return avgMsPerThousandRows;
    }

    public void setAvgMsPerThousandRows(double avgMsPerThousandRows) {
        this.avgMsPerThousandRows = avgMsPerThousandRows;
    }

    public long getEstimatedMinDurationMs() {
        return estimatedMinDurationMs;
    }

    public void setEstimatedMinDurationMs(long estimatedMinDurationMs) {
        this.estimatedMinDurationMs = estimatedMinDurationMs;
    }

    public String getCostSummary() {
        return costSummary;
    }

    public void setCostSummary(String costSummary) {
        this.costSummary = costSummary;
    }

    /** 大表适配建议（文档级） */
    public static class BigTableAdvice {
        private String tableName;
        /** 该表明细 sourceTotal（非任务级 Meta、非明细汇总） */
        private long sourceRows;
        private String advice;

        public BigTableAdvice() {
        }

        public BigTableAdvice(String tableName, long sourceRows, String advice) {
            this.tableName = tableName;
            this.sourceRows = sourceRows;
            this.advice = advice;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public long getSourceRows() {
            return sourceRows;
        }

        public void setSourceRows(long sourceRows) {
            this.sourceRows = sourceRows;
        }

        public String getAdvice() {
            return advice;
        }

        public void setAdvice(String advice) {
            this.advice = advice;
        }
    }

    /** 差异表提示 */
    public static class DiffTableAdvice {
        private String tableName;
        private long diffTotal;
        private String advice;

        public DiffTableAdvice() {
        }

        public DiffTableAdvice(String tableName, long diffTotal, String advice) {
            this.tableName = tableName;
            this.diffTotal = diffTotal;
            this.advice = advice;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public long getDiffTotal() {
            return diffTotal;
        }

        public void setDiffTotal(long diffTotal) {
            this.diffTotal = diffTotal;
        }

        public String getAdvice() {
            return advice;
        }

        public void setAdvice(String advice) {
            this.advice = advice;
        }
    }
}
