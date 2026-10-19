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
     * 是否同步数据
     */
    private boolean enableCopyData;
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
}
