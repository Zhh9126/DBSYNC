/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.parser.strategy.impl;

import com.google.protobuf.ByteString;
import org.dbdatasync.common.binlog.proto.BinlogMap;
import org.dbdatasync.common.enums.TaskLevelEnum;
import org.dbdatasync.common.model.Result;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.LogService;
import org.dbdatasync.parser.LogType;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.flush.BufferActuator;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.StorageRequest;
import org.dbdatasync.parser.model.SystemConfig;
import org.dbdatasync.parser.strategy.FlushStrategy;
import org.dbdatasync.parser.util.MetaLockUtil;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.observer.ReplicationEventObservers;
import org.dbdatasync.sdk.retry.WriteFailureTag;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.MetaIncrement;
import org.dbdatasync.sdk.schema.SchemaResolver;
import org.dbdatasync.storage.enums.StorageDataStatusEnum;
import org.dbdatasync.storage.impl.SnowflakeIdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @version 1.0.0
 * @author AE86
 * @date 2021-11-18 22:22
 */
@Component
public final class FlushStrategyImpl implements FlushStrategy {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private LogService logService;

    @Resource
    private BufferActuator storageBufferActuator;

    @Override
    public void flushFullData(Result result, SchemaResolver targetSchemaResolver, Map<String, Field> targetFieldMap) {
        // 不记录全量数据, 只记录增量同步数据, 将异常记录到系统日志中
        if (!profileComponent.getSystemConfig().isEnableStorageWriteFull()) {
            // 不记录全量数据，只统计成功失败总数
            refreshTotal(result);

            ReplicationEventObservers.notifyRetryRecovered(result.getMetaId(), result.getAutoRetriedRows().get());
            if (!CollectionUtils.isEmpty(result.getFailData())) {
                logger.error(result.getError().toString());
                LogType logType = LogType.TableGroupLog.FULL_FAILED;
                logService.log(logType, "%s:%s:%s", result.getTargetTableGroupName(), logType.getMessage(), result.getError().toString());
                // 全量失败默认不落明细（既有开关），但必须计入死信指标，避免链路健康出现盲区
                String cause = StringUtil.substring(result.getError().toString(), 0, 256);
                for (int i = 0; i < result.getFailData().size(); i++) {
                    ReplicationEventObservers.notifyDeadLetter(result.getMetaId(),
                            WriteFailureTag.kindOf(result.getError().toString()), cause);
                }
            }
            return;
        }

        flush(result, targetSchemaResolver, targetFieldMap);
    }

    @Override
    public void flushIncrementData(Result result, SchemaResolver targetSchemaResolver, Map<String, Field> targetFieldMap) {
        flush(result, targetSchemaResolver, targetFieldMap);
    }

    private void asyncWrite(Result result, SchemaResolver schemaResolver, Map<String, Field> targetFieldMap, boolean success, List<Map> data, String error) {
        String metaId = result.getMetaId();
        // 明细分表键用任务级 Meta.taskId；metaId 仍用于任务级 Meta 计数增量
        String shardId = metaProfile.resolveTaskDetailShardId(metaId);
        String event = result.getEvent();
        String tableGroupId = result.getTableGroupId();
        String targetTableGroupName = result.getTargetTableGroupName();

        long now = Instant.now().toEpochMilli();
        // 失败明细结构化打标：永久故障直接标死信，其余标待处理失败。
        // 仅占用 error 文本列，不改 isSuccess 值域、不加列、不加表。
        // Wave24：逐行失败分类优先。Wave22 的批次口径会让同批瞬时故障行被
        // 「永久优先」误标死信，这里按 Result.rowFailures 逐行还原真实分类。
        final String batchError = success ? error : WriteFailureTag.tag(error, 1);
        data.forEach(r-> {
            // Result 在本类按原始类型使用，get() 需显式转型
            org.dbdatasync.common.model.RowFailure rowFailure = success ? null
                    : (org.dbdatasync.common.model.RowFailure) result.getRowFailures().get(String.valueOf(r));
            final String rowError = success ? batchError : rowErrorText(error, rowFailure);
            final String failureKind = success ? null : WriteFailureTag.kindOf(rowError);
            final boolean deadLetter = !success && WriteFailureTag.isDeadLetter(rowError);
            Map<String, Object> row = new HashMap<>();
            row.put(ConfigConstant.CONFIG_MODEL_ID, String.valueOf(snowflakeIdWorker.nextId()));
            // 明细分表：TABLE_GROUP_ID 关联表映射；事件写入 TYPE；成功状态写入 IS_SUCCESS
            row.put(ConfigConstant.DETAIL_IS_SUCCESS, success ? StorageDataStatusEnum.SUCCESS.getValue() : StorageDataStatusEnum.FAIL.getValue());
            row.put(ConfigConstant.DATA_TABLE_GROUP_ID, tableGroupId);
            row.put(ConfigConstant.DETAIL_TARGET_TABLE, targetTableGroupName);
            row.put(ConfigConstant.CONFIG_MODEL_TYPE, event);
            row.put(ConfigConstant.DATA_ERROR, rowError);
            row.put(ConfigConstant.CONFIG_MODEL_CREATE_TIME, now);
            row.put(ConfigConstant.CONFIG_MODEL_UPDATE_TIME, now);
            try {
                row.put(ConfigConstant.BINLOG_DATA, toBinlogBytes(schemaResolver, r, targetFieldMap));
            } catch (Throwable e) {
                // 构建详细类型信息
                StringBuilder typeInfo = new StringBuilder();
                r.forEach((k, val) -> {
                    typeInfo.append(k).append("=").append(val == null ? "null" : val.getClass().getName()).append("; ");
                });
                logger.warn("可能存在Blob或inputStream大文件类型, 无法序列化。字段类型详情: {}", typeInfo, e);
            }
            storageBufferActuator.offer(new StorageRequest(shardId, row));
            if (deadLetter) {
                ReplicationEventObservers.notifyDeadLetter(metaId, failureKind,
                        StringUtil.substring(rowError, 0, 256));
            }
        });
    }

    /** 由逐行分类结果生成失败明细标签；无逐行结果时回退到批次口径。 */
    private String rowErrorText(String batchError, org.dbdatasync.common.model.RowFailure failure) {
        if (failure == null) {
            return WriteFailureTag.tag(batchError, 1);
        }
        StringBuilder text = new StringBuilder(failure.isPermanent()
                ? WriteFailureTag.DEAD_LETTER : WriteFailureTag.SYNC_FAIL);
        text.append(WriteFailureTag.KIND_PREFIX).append(failure.getKind()).append(']')
                .append(WriteFailureTag.RETRY_PREFIX).append(Math.max(0, failure.getAttempts())).append(']');
        if (failure.getSqlState() != null) {
            text.append("[sqlState=").append(failure.getSqlState()).append(']');
        }
        if (failure.getErrorCode() != 0) {
            text.append("[errorCode=").append(failure.getErrorCode()).append(']');
        }
        return text.append(' ').append(failure.getMessage()).toString();
    }

    private byte[] toBinlogBytes(SchemaResolver schemaResolver, Map<String, Object> data, Map<String, Field> fieldMap) {
        BinlogMap.Builder dataBuilder = BinlogMap.newBuilder();
        data.forEach((k, v)-> {
            if (null != v) {
                // DDL
                if (fieldMap == null) {
                    ByteString bytes = schemaResolver.serialize(v, null);
                    if (null != bytes) {
                        dataBuilder.putRow(k, bytes);
                    }
                    return;
                }

                // DML
                Field field = fieldMap.get(k);
                if (field != null) {
                    ByteString bytes = schemaResolver.serialize(v, field);
                    if (null != bytes) {
                        dataBuilder.putRow(k, bytes);
                    }
                }
            }
        });
        return dataBuilder.build().toByteArray();
    }

    private void refreshTotal(Result writer) {
        int success = writer.getSuccessData().size();
        int fail = writer.getFailData().size();
        if (success == 0 && fail == 0) {
            return;
        }
        // 与 FullPuller snapshot flush 共用锁，避免并发 editConfigModel 回写覆盖 DB 原子 success/fail
        synchronized (MetaLockUtil.lock(writer.getMetaId())) {
            metaProfile.incrementMeta(MetaIncrement.of(writer.getMetaId()).success(success).fail(fail));
        }
        incrementTableMeta(writer.getTableGroupId(), success, fail);
    }

    /**
     * 同步表级 Meta：taskId=table_group.id，isTaskDetail=1；主键由 ADD 路径生成雪花。
     */
    private void incrementTableMeta(String tableGroupId, long success, long fail) {
        if (StringUtil.isBlank(tableGroupId)) {
            return;
        }
        synchronized (("table-meta-" + tableGroupId).intern()) {
            Meta tableMeta = metaProfile.getMetaByTaskId(tableGroupId, TaskLevelEnum.TASK_DETAIL);
            if (tableMeta == null) {
                tableMeta = new Meta();
                tableMeta.setTaskId(tableGroupId);
                tableMeta.setIsTaskDetail(TaskLevelEnum.TASK_DETAIL.getCode());
                long now = Instant.now().toEpochMilli();
                tableMeta.setCreateTime(now);
                tableMeta.setUpdateTime(now);
                profileComponent.addConfigModel(tableMeta);
            }
            metaProfile.incrementMeta(MetaIncrement.of(tableMeta.getId()).success(success).fail(fail));
        }
    }

    private void flush(Result result, SchemaResolver schemaResolver, Map<String, Field> targetFieldMap) {
        refreshTotal(result);
        ReplicationEventObservers.notifyRetryRecovered(result.getMetaId(), result.getAutoRetriedRows().get());
        // Wave23：把本批写入结果回传给重放方，只有明确成功后才允许删除失败明细
        if (StringUtil.isNotBlank(result.getReplayId())) {
            org.dbdatasync.sdk.replay.ReplayConfirmation.mark(result.getMetaId(),
                    result.getReplayId(), CollectionUtils.isEmpty(result.getFailData()));
        }

        SystemConfig systemConfig = profileComponent.getSystemConfig();
        // 是否写失败数据
        if (systemConfig.isEnableStorageWriteFail() && !CollectionUtils.isEmpty(result.getFailData())) {
            final String error = StringUtil.substring(result.getError().toString(), 0, systemConfig.getMaxStorageErrorLength());
            asyncWrite(result, schemaResolver, targetFieldMap, false, result.getFailData(), error);
        }

        // 是否写成功数据
        if (systemConfig.isEnableStorageWriteSuccess() && !CollectionUtils.isEmpty(result.getSuccessData())) {
            asyncWrite(result, schemaResolver, targetFieldMap, true, result.getSuccessData(), "");
        }
    }

}