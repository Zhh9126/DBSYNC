/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.parser.flush.impl;

import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.flush.AbstractBufferActuatorRouter;
import org.dbdatasync.sdk.listener.ChangedEvent;
import org.dbdatasync.sdk.model.BufferActuatorMetric;
import org.dbdatasync.sdk.spi.TableGroupBufferActuatorService;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 开源增量路由：每张源表一个执行器，超过上限后走通用执行器。
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-08-12
 */
public class DefaultBufferActuatorRouter extends AbstractBufferActuatorRouter {

    @Resource
    private TableGroupBufferActuatorService tableGroupBufferActuatorService;

    /**
     * 驱动缓存执行路由列表
     */
    private final Map<String, Map<String, TableGroupBufferActuator>> router = new ConcurrentHashMap<>();

    @Override
    public void execute(String metaId, ChangedEvent event) {
        event.getChangedOffset().setMetaId(metaId);
        // Wave23 事务型 checkpoint：开关打开时计算幂等键，重复事件直接丢弃
        org.dbdatasync.sdk.checkpoint.TransactionalCheckpoint checkpoint =
                org.dbdatasync.sdk.checkpoint.Checkpoints.coordinator();
        if (checkpoint != null && event != null) {
            org.dbdatasync.sdk.model.ChangedOffset offset = event.getChangedOffset();
            String key = org.dbdatasync.sdk.checkpoint.IdempotencyKey.of(metaId,
                    offset.getNextFileName(), offset.getPosition(), offset.getSeq(),
                    event.getSourceTableName(), event.getEvent(), event.getChangedRow());
            if (checkpoint.isDuplicate(metaId, key)) {
                return;
            }
            offset.setIdempotencyKey(key);
        }
        printTraceInfo(event);
        Map<String, TableGroupBufferActuator> processor = router.get(metaId);
        if (processor == null) {
            offer(generalBufferActuator, event);
            return;
        }
        TableGroupBufferActuator actuator = processor.get(event.getSourceTableName());
        if (actuator == null) {
            offer(generalBufferActuator, event);
            return;
        }
        offer(actuator, event);
    }

    @Override
    public void bind(String metaId, List<String> sourceTableNames, int channelSize) {
        if (StringUtil.isBlank(metaId) || sourceTableNames == null) {
            return;
        }
        final int maxBufferActuatorSize = profileComponent.getSystemConfig() == null ? 50
                : profileComponent.getSystemConfig().getMaxBufferActuatorSize();
        router.computeIfAbsent(metaId, k -> {
            Map<String, TableGroupBufferActuator> processor = new ConcurrentHashMap<>();
            for (String tableName : sourceTableNames) {
                if (StringUtil.isBlank(tableName)) {
                    logger.warn("Skip bind tableGroup with empty source table, metaId={}", metaId);
                    continue;
                }
                if (processor.size() >= maxBufferActuatorSize) {
                    logger.warn("Not allowed more than table processor limited size:{}", maxBufferActuatorSize);
                    break;
                }
                if (processor.containsKey(tableName)) {
                    continue;
                }
                try {
                    TableGroupBufferActuator newBufferActuator =
                            (TableGroupBufferActuator) tableGroupBufferActuatorService.clone();
                    newBufferActuator.setTableName(tableName);
                    newBufferActuator.start();
                    processor.put(tableName, newBufferActuator);
                } catch (CloneNotSupportedException ex) {
                    logger.error(ex.getMessage(), ex);
                }
            }
            return processor;
        });
    }

    @Override
    public void unbind(String metaId) {
        router.computeIfPresent(metaId, (k, processor) -> {
            processor.values().forEach(this::stopActuator);
            return null;
        });
    }

    @Override
    public void destroy() {
        router.values().forEach(map -> map.values().forEach(this::stopActuator));
        router.clear();
    }

    @Override
    public long getQueueSize() {
        AtomicLong total = new AtomicLong();
        router.values().forEach(map -> map.values().forEach(actuator -> total.addAndGet(actuator.getQueue().size())));
        return total.get();
    }

    @Override
    public long getQueueCapacity() {
        AtomicLong total = new AtomicLong();
        router.values().forEach(map -> map.values().forEach(actuator -> total.addAndGet(actuator.getQueueCapacity())));
        return total.get();
    }

    @Override
    public List<BufferActuatorMetric> listMetrics() {
        if (router.isEmpty()) {
            return Collections.emptyList();
        }
        List<BufferActuatorMetric> metrics = new ArrayList<>();
        router.forEach((metaId, group) -> group.forEach((tableName, actuator) ->
                metrics.add(toMetric(metaId, actuator.getTableName(), actuator))));
        return metrics;
    }
}
