/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.model;

import org.dbdatasync.common.model.ConfigModel;
import org.dbdatasync.sdk.constant.ConfigConstant;

import java.util.ArrayList;
import java.util.List;

/**
 * 整库迁移任务配置（仅持久化配置到 {@code DBDataSync_task.JSON}）。
 * <p>库映射轻量列表写入 task.JSON（不含表）；表映射存 {@code DBDataSync_table_group}。
 * 运行进度与本轮完成态一律在 {@code DBDataSync_meta}（任务级 STATE/时间/库摘要 + 明细 SNAPSHOT）。</p>
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-05-22 11:19
 */
public class DatabaseSyncTask extends ConfigModel {

    public DatabaseSyncTask() {
        super.setType(ConfigConstant.DATABASE_SYNC);
    }

    /**
     * 是否复制表结构
     */
    private boolean enableCopySchema;
    /**
     * 表结构是否覆盖（目标已存在时）
     */
    private boolean overwriteSchema;
    /**
     * 是否同步数据。
     * <p>Wave30：默认 true。存量任务 JSON 若早于该字段，反序列化保留此默认值，
     * 仍按「整库全量迁移」执行；新建/编辑由 {@code fillSyncStrategy} 按页面开关显式赋值。
     * 这样「仅结构迁移」的开关才真正生效，又不会让历史任务突然停止灌数据。</p>
     */
    private boolean enableCopyData = true;
    /**
     * 数据是否覆盖（目标已存在时）
     */
    private boolean overwriteData;

    /**
     * 库级映射（持久化到 task.JSON，不含 tableMappings）
     */
    private List<DatabaseMapping> databaseMappings = new ArrayList<>();

    /**
     * 分页读取条数
     */
    private int readNum = 10000;

    /**
     * 单次写入条数
     */
    private int batchNum = 1000;

    /**
     * 表级并发线程数（预留，与订正校验 threadNum 一致）
     */
    private int threadNum = 5;

    /**
     * 一键接续增量创建的驱动（Mapping）ID 列表，与迁移任务关联
     */
    private List<String> mappingIds = new ArrayList<>();

    /**
     * 新表自动发现：每次启动运行时扫描源端库表清单，
     * 发现源端新增表 → 自动建目标表（enableCopySchema 开启时）+ 追加表映射 + 全量同步
     */
    private boolean enableAutoDiscover;

    /**
     * 迁移编排阶段: schedule(编排就绪) → full(全量迁移) → increment(增量追平) → validate(一致性校验) → switchover(切换)
     * <p>由 {@code DatabaseSyncServiceImpl} 推进；持久化到 Meta.SNAPSHOT（migrationStage），不随任务配置导出。</p>
     */
    private String migrationStage = "schedule";

    public String getMigrationStage() {
        return migrationStage;
    }

    public void setMigrationStage(String migrationStage) {
        this.migrationStage = migrationStage == null ? "schedule" : migrationStage;
    }

    public boolean isEnableAutoDiscover() {
        return enableAutoDiscover;
    }

    public void setEnableAutoDiscover(boolean enableAutoDiscover) {
        this.enableAutoDiscover = enableAutoDiscover;
    }

    public boolean isEnableCopySchema() {
        return enableCopySchema;
    }

    public void setEnableCopySchema(boolean enableCopySchema) {
        this.enableCopySchema = enableCopySchema;
    }

    public boolean isOverwriteSchema() {
        return overwriteSchema;
    }

    public void setOverwriteSchema(boolean overwriteSchema) {
        this.overwriteSchema = overwriteSchema;
    }

    public boolean isEnableCopyData() {
        return enableCopyData;
    }

    public void setEnableCopyData(boolean enableCopyData) {
        this.enableCopyData = enableCopyData;
    }

    public boolean isOverwriteData() {
        return overwriteData;
    }

    public void setOverwriteData(boolean overwriteData) {
        this.overwriteData = overwriteData;
    }

    public List<DatabaseMapping> getDatabaseMappings() {
        return databaseMappings;
    }

    public void setDatabaseMappings(List<DatabaseMapping> databaseMappings) {
        this.databaseMappings = databaseMappings == null ? new ArrayList<>() : databaseMappings;
    }

    public int getReadNum() {
        return readNum;
    }

    public void setReadNum(int readNum) {
        this.readNum = readNum;
    }

    public int getBatchNum() {
        return batchNum;
    }

    public void setBatchNum(int batchNum) {
        this.batchNum = batchNum;
    }

    public int getThreadNum() {
        return threadNum;
    }

    public void setThreadNum(int threadNum) {
        this.threadNum = threadNum;
    }

    public List<String> getMappingIds() {
        return mappingIds;
    }

    public void setMappingIds(List<String> mappingIds) {
        this.mappingIds = mappingIds == null ? new ArrayList<>() : mappingIds;
    }

    /**
     * 每秒最多迁移行数（0 = 不限速）
     */
    private long recordsPerSecond = 0;
    /**
     * 每秒最多迁移字节数（0 = 不限速）
     */
    private long bytesPerSecond = 0;
    /**
     * 是否仅在低峰时间窗内启动
     */
    private boolean enableOffPeakWindow = false;
    /**
     * 低峰窗口开始时间（HH:mm，24 小时制）
     */
    private String windowStart = "";
    /**
     * 低峰窗口结束时间（HH:mm，24 小时制）；start &gt; end 表示跨越 24:00
     */
    private String windowEnd = "";
    /**
     * 全量完成后是否自动推进到增量追平阶段
     */
    private boolean autoAdvanceStage = false;

    public long getRecordsPerSecond() {
        return recordsPerSecond;
    }

    public void setRecordsPerSecond(long recordsPerSecond) {
        this.recordsPerSecond = recordsPerSecond;
    }

    public long getBytesPerSecond() {
        return bytesPerSecond;
    }

    public void setBytesPerSecond(long bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
    }

    public boolean isEnableOffPeakWindow() {
        return enableOffPeakWindow;
    }

    public void setEnableOffPeakWindow(boolean enableOffPeakWindow) {
        this.enableOffPeakWindow = enableOffPeakWindow;
    }

    public String getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(String windowStart) {
        this.windowStart = windowStart == null ? "" : windowStart;
    }

    public String getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(String windowEnd) {
        this.windowEnd = windowEnd == null ? "" : windowEnd;
    }

    public boolean isAutoAdvanceStage() {
        return autoAdvanceStage;
    }

    public void setAutoAdvanceStage(boolean autoAdvanceStage) {
        this.autoAdvanceStage = autoAdvanceStage;
    }
}
