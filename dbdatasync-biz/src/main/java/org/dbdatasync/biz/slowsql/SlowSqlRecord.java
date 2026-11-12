package org.dbdatasync.biz.slowsql;

/**
 * 慢 SQL 采集记录：一次 SQL 执行的耗时与来源快照。
 *
 * <p>纯 POJO，不依赖 JDBC / 数据库连接；由采集端填充字段后
 * 交给 {@link SlowSqlCollector} 入库。{@code thresholdExceeded}
 * 通常由存储端在 {@code collect} 时依据阈值统一标记。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class SlowSqlRecord {

    /** SQL 文本 */
    private String sql;

    /** 来源标识（任务 / 连接器） */
    private String source;

    /** 执行耗时（毫秒） */
    private long executeMillis;

    /** 影响或返回的行数 */
    private long rows;

    /** 采集时间（epoch 毫秒） */
    private long capturedAt;

    /** 是否超过慢 SQL 阈值 */
    private boolean thresholdExceeded;

    /** Wave24：所属同步任务 ID（由引擎观察事件 source 的 |task= 段解析，可为空） */
    private String taskId;

    public SlowSqlRecord() {
    }

    /**
     * 全参构造。
     *
     * @param sql               SQL 文本
     * @param source            来源标识（任务 / 连接器）
     * @param executeMillis     执行耗时（毫秒）
     * @param rows              行数
     * @param capturedAt        采集时间（epoch 毫秒）
     * @param thresholdExceeded 是否超过慢 SQL 阈值
     */
    public SlowSqlRecord(String sql, String source, long executeMillis, long rows,
                         long capturedAt, boolean thresholdExceeded) {
        this.sql = sql;
        this.source = source;
        this.executeMillis = executeMillis;
        this.rows = rows;
        this.capturedAt = capturedAt;
        this.thresholdExceeded = thresholdExceeded;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public long getExecuteMillis() {
        return executeMillis;
    }

    public void setExecuteMillis(long executeMillis) {
        this.executeMillis = executeMillis;
    }

    public long getRows() {
        return rows;
    }

    public void setRows(long rows) {
        this.rows = rows;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(long capturedAt) {
        this.capturedAt = capturedAt;
    }

    public boolean isThresholdExceeded() {
        return thresholdExceeded;
    }

    public void setThresholdExceeded(boolean thresholdExceeded) {
        this.thresholdExceeded = thresholdExceeded;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }
}
