/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.connector.kafka.config;

import org.dbdatasync.connector.kafka.util.KafkaUtil;
import org.dbdatasync.sdk.model.ConnectorConfig;

/**
 * Kafka连接配置
 *
 * @Author AE86
 * @Version 1.0.0
 * @Date 2021-11-04 20:10
 */
public class KafkaConfig extends ConnectorConfig {

    private String url;

    @Override
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public String getPropertiesText() {
        // 支持换行显示
        return KafkaUtil.toString(getProperties());
    }
}
