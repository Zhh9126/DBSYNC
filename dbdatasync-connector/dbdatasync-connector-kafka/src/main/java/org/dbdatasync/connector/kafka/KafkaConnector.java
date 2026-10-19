/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.connector.kafka;

import org.dbdatasync.common.model.Result;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.kafka.cdc.KafkaListener;
import org.dbdatasync.connector.kafka.config.KafkaConfig;
import org.dbdatasync.connector.kafka.constant.KafkaConstant;
import org.dbdatasync.connector.kafka.schema.KafkaSchemaResolver;
import org.dbdatasync.connector.kafka.util.KafkaMessageUtil;
import org.dbdatasync.connector.kafka.validator.KafkaConfigValidator;
import org.dbdatasync.sdk.config.CommandConfig;
import org.dbdatasync.sdk.connector.AbstractConnector;
import org.dbdatasync.sdk.connector.ConfigValidator;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.connector.ConnectorServiceContext;
import org.dbdatasync.sdk.constant.ConnectorConstant;
import org.dbdatasync.sdk.enums.ListenerTypeEnum;
import org.dbdatasync.sdk.enums.TableTypeEnum;
import org.dbdatasync.sdk.listener.Listener;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.MetaInfo;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.plugin.MetaContext;
import org.dbdatasync.sdk.plugin.PluginContext;
import org.dbdatasync.sdk.plugin.ReaderContext;
import org.dbdatasync.sdk.schema.SchemaResolver;
import org.dbdatasync.sdk.spi.ConnectorService;
import org.dbdatasync.sdk.util.PrimaryKeyUtil;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kafka连接器实现
 *
 * @Author AE86
 * @Version 1.0.0
 * @Date 2021-11-22 23:55
 */
public class KafkaConnector extends AbstractConnector implements ConnectorService<KafkaConnectorInstance, KafkaConfig> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final KafkaConfigValidator configValidator = new KafkaConfigValidator();
    private final KafkaSchemaResolver schemaResolver = new KafkaSchemaResolver();

    @Override
    public String getConnectorType() {
        return "Kafka";
    }

    @Override
    public TableTypeEnum getExtendedTableType() {
        return TableTypeEnum.SEMI;
    }

    @Override
    public Class<KafkaConfig> getConfigClass() {
        return KafkaConfig.class;
    }

    @Override
    public ConnectorInstance connect(KafkaConfig config, ConnectorServiceContext context) {
        return new KafkaConnectorInstance(config);
    }

    @Override
    public ConfigValidator getConfigValidator() {
        return configValidator;
    }

    @Override
    public void disconnect(KafkaConnectorInstance connectorInstance) {
        connectorInstance.close();
    }

    @Override
    public boolean isAlive(KafkaConnectorInstance connectorInstance) {
        try {
            connectorInstance.ping();
            return true;
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            throw new KafkaException(e);
        }
    }

    @Override
    public List<String> getDatabases(KafkaConnectorInstance connectorInstance) {
        try {
            List<String> db = new ArrayList<>();
            String clusterId = connectorInstance.getClusterId();
            if (StringUtil.isNotBlank(clusterId)) {
                db.add(clusterId);
            }
            return db;
        } catch (Exception e) {
            throw new KafkaException(e);
        }
    }

    @Override
    public List<Table> getTable(KafkaConnectorInstance connectorInstance, ConnectorServiceContext context) {
        return new ArrayList<>();
    }

    @Override
    public List<MetaInfo> getMetaInfo(KafkaConnectorInstance connectorInstance, ConnectorServiceContext context) {
        List<MetaInfo> metaInfos = new ArrayList<>();
        for (Table table : context.getTablePatterns()) {
            MetaInfo metaInfo = new MetaInfo();
            metaInfo.setTable(table.getName());
            metaInfo.setTableType(getExtendedTableType().getCode());
            metaInfo.setColumn(table.getColumn());
            metaInfo.setExtInfo(table.getExtInfo());
            metaInfos.add(metaInfo);
        }
        return metaInfos;
    }

    @Override
    public long getCount(KafkaConnectorInstance connectorInstance, MetaContext metaContext) {
        return 0;
    }

    @Override
    public Result reader(KafkaConnectorInstance connectorInstance, ReaderContext context) {
        throw new KafkaException("Kafka暂不支持全量读取");
    }

    @Override
    public Result writer(KafkaConnectorInstance connectorInstance, PluginContext context) {
        List<Map> data = context.getTargetList();
        if (CollectionUtils.isEmpty(data)) {
            logger.error("writer data can not be empty.");
            throw new KafkaException("writer data can not be empty.");
        }

        Result result = new Result();
        List<Field> pkFields = PrimaryKeyUtil.findExistPrimaryKeyFields(context.getTargetFields());
        String topic = context.getCommand().get(KafkaConstant.TOPIC);
        String sourceTableName = context.getSourceTable().getName();
        String event = StringUtil.isBlank(context.getEvent()) ? ConnectorConstant.OPERTION_INSERT : context.getEvent();
        try {
            KafkaProducer<String, Object> producer = connectorInstance.getProducer(topic);
            for (Map row : data) {
                try {
                    Map<String, Object> message = KafkaMessageUtil.buildMessage(sourceTableName, event, row);
                    String key = KafkaMessageUtil.buildMessageKey(pkFields, row);
                    producer.send(new ProducerRecord<>(topic, key, message));
                    result.getSuccessData().add(row);
                } catch (Exception e) {
                    result.getFailData().add(row);
                    result.getError().append(e.getMessage()).append(System.lineSeparator());
                    logger.error(e.getMessage(), e);
                }
            }
            producer.flush();
        } catch (Exception e) {
            result.addFailData(data);
            result.getError().append(e.getMessage()).append(System.lineSeparator());
            logger.error(e.getMessage(), e);
        }
        return result;
    }

    @Override
    public Map<String, String> getSourceCommand(CommandConfig commandConfig) {
        return new HashMap<>();
    }

    @Override
    public Map<String, String> getTargetCommand(CommandConfig commandConfig) {
        Map<String, String> cmd = new HashMap<>();
        cmd.put(KafkaConstant.TOPIC, commandConfig.getTable().getName());
        return cmd;
    }

    @Override
    public Listener getListener(String listenerType) {
        if (ListenerTypeEnum.isLog(listenerType)) {
            return new KafkaListener();
        }
        return null;
    }

    @Override
    public SchemaResolver getSchemaResolver() {
        return schemaResolver;
    }
}
