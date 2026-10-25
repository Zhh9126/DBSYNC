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
     */
    private boolean enableStorageWriteSuccess;

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
     */
    private boolean enableStorageWriteFull;

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
}
