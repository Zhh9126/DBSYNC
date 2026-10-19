/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.kafka.schema;

import org.dbdatasync.connector.kafka.KafkaException;
import org.dbdatasync.connector.kafka.schema.support.KafkaBooleanType;
import org.dbdatasync.connector.kafka.schema.support.KafkaByteType;
import org.dbdatasync.connector.kafka.schema.support.KafkaBytesType;
import org.dbdatasync.connector.kafka.schema.support.KafkaDateType;
import org.dbdatasync.connector.kafka.schema.support.KafkaDecimalType;
import org.dbdatasync.connector.kafka.schema.support.KafkaDoubleType;
import org.dbdatasync.connector.kafka.schema.support.KafkaFloatType;
import org.dbdatasync.connector.kafka.schema.support.KafkaIntType;
import org.dbdatasync.connector.kafka.schema.support.KafkaLongType;
import org.dbdatasync.connector.kafka.schema.support.KafkaShortType;
import org.dbdatasync.connector.kafka.schema.support.KafkaStringType;
import org.dbdatasync.connector.kafka.schema.support.KafkaTimeType;
import org.dbdatasync.connector.kafka.schema.support.KafkaTimestampType;
import org.dbdatasync.sdk.schema.AbstractSchemaResolver;
import org.dbdatasync.sdk.schema.DataType;

import java.util.Map;
import java.util.stream.Stream;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-01-11 22:18
 */
public class KafkaSchemaResolver extends AbstractSchemaResolver {

    @Override
    protected void initDataTypeMapping(Map<String, DataType> mapping) {
        Stream.of(new KafkaStringType(), new KafkaIntType(), new KafkaShortType(), new KafkaLongType(), new KafkaDecimalType(), new KafkaFloatType(), new KafkaDoubleType(), new KafkaDateType(), new KafkaTimestampType(), new KafkaTimeType(), new KafkaBooleanType(), new KafkaBytesType(), new KafkaByteType())
                .forEach(t->t.getSupportedTypeName().forEach(typeName-> {
                    if (mapping.containsKey(typeName)) {
                        throw new KafkaException("Duplicate type name: " + typeName);
                    }
                    mapping.put(typeName, t);
                }));
    }
}