/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.rocketmq.validator;

import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.rocketmq.RocketMQConnector;
import org.dbdatasync.connector.rocketmq.config.RocketMQConfig;
import org.dbdatasync.connector.rocketmq.util.RocketMQUtil;
import org.dbdatasync.sdk.connector.ConfigValidator;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.Table;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-07 01:00
 */
public class RocketMQConfigValidator implements ConfigValidator<RocketMQConnector, RocketMQConfig> {

    @Override
    public void modify(RocketMQConnector connectorService, RocketMQConfig connectorConfig, Map<String, String> params) {
        String url = params.get("url");
        String properties = params.get("properties");
        String producerProperties = params.get(RocketMQUtil.PRODUCER_PROPERTIES);
        String consumerProperties = params.get(RocketMQUtil.CONSUMER_PROPERTIES);
        if (producerProperties == null && consumerProperties == null) {
            String extInfo = params.get("extInfo");
            Assert.hasText(extInfo, "扩展参数不能为空");
            Properties props = JsonUtil.jsonToObj(extInfo, Properties.class);
            connectorConfig.getExtInfo().putAll(props != null ? props : new Properties());
        } else {
            Assert.hasText(producerProperties, "生产者参数不能为空");
            Assert.hasText(consumerProperties, "消费者参数不能为空");
            connectorConfig.getExtInfo().put(RocketMQUtil.PRODUCER_PROPERTIES, producerProperties);
            connectorConfig.getExtInfo().put(RocketMQUtil.CONSUMER_PROPERTIES, consumerProperties);
        }
        Assert.hasText(url, "url is empty.");
        if (StringUtil.isNotBlank(properties)) {
            connectorConfig.getProperties().putAll(RocketMQUtil.parse(properties));
        }
        connectorConfig.setUrl(url);
    }

    @Override
    public Table modifyExtendedTable(RocketMQConnector connectorService, Map<String, String> params) {
        Table table = new Table();
        String tableName = params.get("tableName");
        String columnList = params.get("columnList");
        String groupId = params.get("groupId");
        String tags = params.get("tags");
        Assert.hasText(tableName, "TableName is empty");
        Assert.hasText(columnList, "ColumnList is empty");
        List<Field> fields = JsonUtil.jsonToArray(columnList, Field.class);
        Assert.notEmpty(fields, "字段不能为空.");
        table.setName(tableName);
        table.setColumn(fields);
        table.setType(connectorService.getExtendedTableType().getCode());
        if (StringUtil.isNotBlank(groupId)) {
            table.getExtInfo().put("groupId", groupId);
        }
        if (StringUtil.isNotBlank(tags)) {
            table.getExtInfo().put("tags", tags);
        }
        return table;
    }
}
