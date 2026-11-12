/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.parser.model;

import org.dbdatasync.common.model.ApiKeyConfig;
import org.dbdatasync.common.model.ConfigModel;
import org.dbdatasync.common.model.IpWhitelistConfig;
import org.dbdatasync.common.model.JwtSecretConfig;
import org.dbdatasync.common.model.RsaConfig;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.model.NoticeConfig;

/**
 * 系统配置
 *
 * @version 1.0.0
 * @author AE86
 * @date 2020-05-29 20:13
 */
public class SystemConfig extends ConfigModel {

    public SystemConfig() {
        super.setType(ConfigConstant.SYSTEM);
    }

    /**
     * 同步数据过期时间（天）
     */
    private int expireDataDays = 30;

    /**
     * 系统日志过期时间（天）
     */
    private int expireLogDays = 30;

    /**
     * 日志文件过期时间（天）
     */
    private int expireFileLogDays = 15;

    /**
     * 是否记录同步成功数据（false-关闭; true-开启）
     * Wave32：默认改为开启。此前默认 false 导致「同步结果有成功数、事件明细却暂无数据」，
     * 用户误以为数据丢失；实际是成功明细从未写入 DBDataSync_task_detail 分表。
     */
    private boolean enableStorageWriteSuccess = true;

    /**
     * 是否记录同步失败数据（false-关闭; true-开启）
     */
    private boolean enableStorageWriteFail = true;

    /**
     * 记录同步失败日志最大长度
     */
    private int maxStorageErrorLength = 8192;

    /**
     * 是否记录全量数据（false-关闭; true-开启）
     * Wave32：默认改为开启，保证全量同步的逐行事件同样落 H2 分表。
     */
    private boolean enableStorageWriteFull = true;

    /**
     * 是否启用水印
     */
    private boolean enableWatermark;

    /**
     * 水印内容
     */
    private String watermark;

    /**
     * 表执行器上限数
     */
    private int maxBufferActuatorSize = 50;

    /**
     * 驱动(同步任务)总数上限, <=0 表示不限制
     */
    private int maxMappingSize = 100;

    /**
     * 运行中驱动(同步任务)并发上限, <=0 表示不限制
     */
    private int maxRunningMappingSize = 20;

    /**
     * DDL 同步风险策略(all=全部 ALTER 自动同步; add_only=仅新增字段自动同步, 其余拦截; off=关闭 DDL 同步)。
     * 对标 Schema Change 风险分级: 低风险自动传播, 高风险(改类型/删列/改名)需人工确认后手动处理。
     */
    private String ddlSyncPolicy = "all";

    /**
     * 是否打印trace信息（false-关闭; true-开启）
     */
    private boolean enablePrintTraceInfo;

    /**
     * 是否开放API
     */
    private boolean enableOpenAPI;

    /**
     * RSA配置
     */
    private RsaConfig rsaConfig;

    /**
     * JWT密钥配置
     */
    private JwtSecretConfig jwtSecretConfig;

    /**
     * API密钥配置（客户端凭证）
     */
    private ApiKeyConfig apiKeyConfig;

    /**
     * IP白名单配置
     */
    private IpWhitelistConfig ipWhitelistConfig;

    /**
     * 通知配置
     */
    private NoticeConfig noticeConfig;

    // ==================== 写入可靠性（Wave22） ====================

    /** 是否开启瞬时写入故障的自动重试（默认开启，可在系统配置页关闭） */
    private boolean enableWriteRetry = true;

    /** 单行最大写入尝试次数（含首次，默认 3） */
    private int writeRetryMaxAttempts = 3;

    /** 指数退避基础毫秒（默认 50） */
    private int writeRetryBaseMillis = 50;

    /** 单行重试总时间预算毫秒（默认 2000） */
    private int writeRetryMaxMillis = 2000;

    // ==================== 实时链路健康与可观测性（Wave22） ====================

    /** 是否开启链路健康告警 */
    private boolean enableReplicationAlarm = true;

    /** 链路停滞告警阈值（秒，默认 60） */
    private int lagWarnSeconds = 60;

    /** 链路停滞严重阈值（秒，默认 300） */
    private int lagCriticalSeconds = 300;

    /** 失败率告警阈值（百分比，默认 5） */
    private int failRateWarnPercent = 5;

    /** 同一链路告警冷却（秒，默认 600） */
    private int healthAlarmCooldownSeconds = 600;

    /** 是否对外输出 Prometheus 文本指标 */
    private boolean enablePrometheus = true;

    public int getExpireDataDays() {
        return expireDataDays;
    }

    public void setExpireDataDays(int expireDataDays) {
        this.expireDataDays = expireDataDays;
    }

    public int getExpireLogDays() {
        return expireLogDays;
    }

    public void setExpireLogDays(int expireLogDays) {
        this.expireLogDays = expireLogDays;
    }

    public int getExpireFileLogDays() {
        return expireFileLogDays;
    }

    public void setExpireFileLogDays(int expireFileLogDays) {
        this.expireFileLogDays = expireFileLogDays;
    }

    public boolean isEnableStorageWriteSuccess() {
        return enableStorageWriteSuccess;
    }

    public void setEnableStorageWriteSuccess(boolean enableStorageWriteSuccess) {
        this.enableStorageWriteSuccess = enableStorageWriteSuccess;
    }

    public boolean isEnableStorageWriteFail() {
        return enableStorageWriteFail;
    }

    public void setEnableStorageWriteFail(boolean enableStorageWriteFail) {
        this.enableStorageWriteFail = enableStorageWriteFail;
    }

    public int getMaxStorageErrorLength() {
        return maxStorageErrorLength;
    }

    public void setMaxStorageErrorLength(int maxStorageErrorLength) {
        this.maxStorageErrorLength = maxStorageErrorLength;
    }

    public boolean isEnableStorageWriteFull() {
        return enableStorageWriteFull;
    }

    public void setEnableStorageWriteFull(boolean enableStorageWriteFull) {
        this.enableStorageWriteFull = enableStorageWriteFull;
    }

    public boolean isEnableWatermark() {
        return enableWatermark;
    }

    public void setEnableWatermark(boolean enableWatermark) {
        this.enableWatermark = enableWatermark;
    }

    public String getWatermark() {
        return watermark;
    }

    public void setWatermark(String watermark) {
        this.watermark = watermark;
    }

    public int getMaxBufferActuatorSize() {
        return maxBufferActuatorSize;
    }

    public void setMaxBufferActuatorSize(int maxBufferActuatorSize) {
        this.maxBufferActuatorSize = maxBufferActuatorSize;
    }

    public boolean isEnablePrintTraceInfo() {
        return enablePrintTraceInfo;
    }

    public void setEnablePrintTraceInfo(boolean enablePrintTraceInfo) {
        this.enablePrintTraceInfo = enablePrintTraceInfo;
    }

    public boolean isEnableOpenAPI() {
        return enableOpenAPI;
    }

    public void setEnableOpenAPI(boolean enableOpenAPI) {
        this.enableOpenAPI = enableOpenAPI;
    }

    public RsaConfig getRsaConfig() {
        return rsaConfig;
    }

    public void setRsaConfig(RsaConfig rsaConfig) {
        this.rsaConfig = rsaConfig;
    }

    public JwtSecretConfig getJwtSecretConfig() {
        return jwtSecretConfig;
    }

    public void setJwtSecretConfig(JwtSecretConfig jwtSecretConfig) {
        this.jwtSecretConfig = jwtSecretConfig;
    }

    public ApiKeyConfig getApiKeyConfig() {
        return apiKeyConfig;
    }

    public void setApiKeyConfig(ApiKeyConfig apiKeyConfig) {
        this.apiKeyConfig = apiKeyConfig;
    }

    public IpWhitelistConfig getIpWhitelistConfig() {
        return ipWhitelistConfig;
    }

    public void setIpWhitelistConfig(IpWhitelistConfig ipWhitelistConfig) {
        this.ipWhitelistConfig = ipWhitelistConfig;
    }

    public NoticeConfig getNoticeConfig() {
        return noticeConfig;
    }

    public void setNoticeConfig(NoticeConfig noticeConfig) {
        this.noticeConfig = noticeConfig;
    }

    public int getMaxMappingSize() {
        return maxMappingSize;
    }

    public void setMaxMappingSize(int maxMappingSize) {
        this.maxMappingSize = maxMappingSize;
    }

    public int getMaxRunningMappingSize() {
        return maxRunningMappingSize;
    }

    public void setMaxRunningMappingSize(int maxRunningMappingSize) {
        this.maxRunningMappingSize = maxRunningMappingSize;
    }

    public String getDdlSyncPolicy() {
        return ddlSyncPolicy;
    }

    public void setDdlSyncPolicy(String ddlSyncPolicy) {
        this.ddlSyncPolicy = ddlSyncPolicy;
    }

    public boolean isEnableWriteRetry() {
        return enableWriteRetry;
    }

    public void setEnableWriteRetry(boolean enableWriteRetry) {
        this.enableWriteRetry = enableWriteRetry;
    }

    public int getWriteRetryMaxAttempts() {
        return writeRetryMaxAttempts;
    }

    public void setWriteRetryMaxAttempts(int writeRetryMaxAttempts) {
        this.writeRetryMaxAttempts = writeRetryMaxAttempts;
    }

    public int getWriteRetryBaseMillis() {
        return writeRetryBaseMillis;
    }

    public void setWriteRetryBaseMillis(int writeRetryBaseMillis) {
        this.writeRetryBaseMillis = writeRetryBaseMillis;
    }

    public int getWriteRetryMaxMillis() {
        return writeRetryMaxMillis;
    }

    public void setWriteRetryMaxMillis(int writeRetryMaxMillis) {
        this.writeRetryMaxMillis = writeRetryMaxMillis;
    }

    public boolean isEnableReplicationAlarm() {
        return enableReplicationAlarm;
    }

    public void setEnableReplicationAlarm(boolean enableReplicationAlarm) {
        this.enableReplicationAlarm = enableReplicationAlarm;
    }

    public int getLagWarnSeconds() {
        return lagWarnSeconds;
    }

    public void setLagWarnSeconds(int lagWarnSeconds) {
        this.lagWarnSeconds = lagWarnSeconds;
    }

    public int getLagCriticalSeconds() {
        return lagCriticalSeconds;
    }

    public void setLagCriticalSeconds(int lagCriticalSeconds) {
        this.lagCriticalSeconds = lagCriticalSeconds;
    }

    public int getFailRateWarnPercent() {
        return failRateWarnPercent;
    }

    public void setFailRateWarnPercent(int failRateWarnPercent) {
        this.failRateWarnPercent = failRateWarnPercent;
    }

    public int getHealthAlarmCooldownSeconds() {
        return healthAlarmCooldownSeconds;
    }

    public void setHealthAlarmCooldownSeconds(int healthAlarmCooldownSeconds) {
        this.healthAlarmCooldownSeconds = healthAlarmCooldownSeconds;
    }

    public boolean isEnablePrometheus() {
        return enablePrometheus;
    }

    public void setEnablePrometheus(boolean enablePrometheus) {
        this.enablePrometheus = enablePrometheus;
    }


    /** Wave23 */
    private boolean enableTransactionalCheckpoint = false;
    /** Wave23 */
    private String checkpointDir = "data/checkpoints";
    /** Wave23 */
    private int checkpointMaxKeys = 200000;
    /** Wave23 */
    private boolean enableClusterHA = false;
    /** Wave23 */
    private String clusterLeaseDir = "data/cluster";
    /** Wave23 */
    private boolean enableStrictSecurity = false;
    /** Wave23 */
    private boolean enableDeadLetterAutoReplay = false;
    /** Wave23 */
    private int deadLetterReplayMaxAttempts = 3;
    /** Wave23 */
    private int deadLetterReplayBaseMillis = 500;
    /** Wave23 */
    private boolean enableNoPkFallback = true;

    /** Wave24：表级 DDL 自动同步（高风险类型仍需人工确认；默认关闭） */
    private boolean enableDdlAutoSync = false;
    /** Wave24：DDL-DML 顺序一致性守卫（未确认 DDL 时挂起同表 DML；默认开启） */
    private boolean enableDdlDmlOrderGuard = true;
    /** Wave24：schema history 落盘目录 */
    private String schemaHistoryDir = "data/schema-history";
    /** Wave24：自动切流 / 真实回滚执行器（默认关闭，只做状态机与预案） */
    private boolean enableCutoverAutoExecute = false;
    /** Wave24：把位点写入目标库同一事务（需先建 __DBSYNC_CHECKPOINT 表；默认关闭） */
    private boolean enableCheckpointInTargetTx = false;

    public boolean isEnableCheckpointInTargetTx() {
        return enableCheckpointInTargetTx;
    }

    public void setEnableCheckpointInTargetTx(boolean enableCheckpointInTargetTx) {
        this.enableCheckpointInTargetTx = enableCheckpointInTargetTx;
    }

    public boolean isEnableDdlAutoSync() {
        return enableDdlAutoSync;
    }

    public void setEnableDdlAutoSync(boolean enableDdlAutoSync) {
        this.enableDdlAutoSync = enableDdlAutoSync;
    }

    public boolean isEnableDdlDmlOrderGuard() {
        return enableDdlDmlOrderGuard;
    }

    public void setEnableDdlDmlOrderGuard(boolean enableDdlDmlOrderGuard) {
        this.enableDdlDmlOrderGuard = enableDdlDmlOrderGuard;
    }

    public String getSchemaHistoryDir() {
        return schemaHistoryDir;
    }

    public void setSchemaHistoryDir(String schemaHistoryDir) {
        this.schemaHistoryDir = schemaHistoryDir;
    }

    public boolean isEnableCutoverAutoExecute() {
        return enableCutoverAutoExecute;
    }

    public void setEnableCutoverAutoExecute(boolean enableCutoverAutoExecute) {
        this.enableCutoverAutoExecute = enableCutoverAutoExecute;
    }

    public boolean isEnableTransactionalCheckpoint() {
        return enableTransactionalCheckpoint;
    }

    public void setEnableTransactionalCheckpoint(boolean enableTransactionalCheckpoint) {
        this.enableTransactionalCheckpoint = enableTransactionalCheckpoint;
    }

    public String getCheckpointDir() {
        return checkpointDir;
    }

    public void setCheckpointDir(String checkpointDir) {
        this.checkpointDir = checkpointDir;
    }

    public int getCheckpointMaxKeys() {
        return checkpointMaxKeys;
    }

    public void setCheckpointMaxKeys(int checkpointMaxKeys) {
        this.checkpointMaxKeys = checkpointMaxKeys;
    }

    public boolean isEnableClusterHA() {
        return enableClusterHA;
    }

    public void setEnableClusterHA(boolean enableClusterHA) {
        this.enableClusterHA = enableClusterHA;
    }

    public String getClusterLeaseDir() {
        return clusterLeaseDir;
    }

    public void setClusterLeaseDir(String clusterLeaseDir) {
        this.clusterLeaseDir = clusterLeaseDir;
    }

    public boolean isEnableStrictSecurity() {
        return enableStrictSecurity;
    }

    public void setEnableStrictSecurity(boolean enableStrictSecurity) {
        this.enableStrictSecurity = enableStrictSecurity;
    }

    public boolean isEnableDeadLetterAutoReplay() {
        return enableDeadLetterAutoReplay;
    }

    public void setEnableDeadLetterAutoReplay(boolean enableDeadLetterAutoReplay) {
        this.enableDeadLetterAutoReplay = enableDeadLetterAutoReplay;
    }

    public int getDeadLetterReplayMaxAttempts() {
        return deadLetterReplayMaxAttempts;
    }

    public void setDeadLetterReplayMaxAttempts(int deadLetterReplayMaxAttempts) {
        this.deadLetterReplayMaxAttempts = deadLetterReplayMaxAttempts;
    }

    public int getDeadLetterReplayBaseMillis() {
        return deadLetterReplayBaseMillis;
    }

    public void setDeadLetterReplayBaseMillis(int deadLetterReplayBaseMillis) {
        this.deadLetterReplayBaseMillis = deadLetterReplayBaseMillis;
    }

    public boolean isEnableNoPkFallback() {
        return enableNoPkFallback;
    }

    public void setEnableNoPkFallback(boolean enableNoPkFallback) {
        this.enableNoPkFallback = enableNoPkFallback;
    }
}
