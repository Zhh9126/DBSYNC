/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.connector.postgresql.decoder;

import org.dbdatasync.sdk.config.DatabaseConfig;
import org.dbdatasync.sdk.connector.database.DatabaseConnectorInstance;
import org.dbdatasync.sdk.listener.event.RowChangedEvent;
import org.dbdatasync.sdk.spi.ConnectorService;

import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;

import java.nio.ByteBuffer;

/**
 * @Author AE86
 * @Version 1.0.0
 * @Date 2022-04-10 22:36
 */
public interface MessageDecoder {

    default void postProcessBeforeInitialization(ConnectorService connectorService, DatabaseConnectorInstance connectorInstance, String database) {
    }

    boolean skipMessage(ByteBuffer buffer, LogSequenceNumber startLsn, LogSequenceNumber lastReceiveLsn);

    RowChangedEvent processMessage(ByteBuffer buffer);

    /**
     * Wave24：最近一次 BEGIN/COMMIT 消息携带的源端事务时间（epoch millis）。
     * test_decoding 插件输出不含时间戳，默认返回 0，由 Listener 退化为估算。
     */
    default long lastSourceEventTimeMillis() {
        return 0L;
    }

    String getSlotName();

    String getOutputPlugin();

    void withSlotOption(ChainedLogicalStreamBuilder builder);

    void setMetaId(String metaId);

    void setConfig(DatabaseConfig config);

    void setDatabase(String database);

    void setSchema(String schema);
}
