/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.h2.schema;

import org.dbdatasync.connector.h2.H2Exception;
import org.dbdatasync.connector.h2.schema.support.H2BooleanType;
import org.dbdatasync.connector.h2.schema.support.H2BytesType;
import org.dbdatasync.connector.h2.schema.support.H2DoubleType;
import org.dbdatasync.connector.h2.schema.support.H2IntType;
import org.dbdatasync.connector.h2.schema.support.H2LongType;
import org.dbdatasync.connector.h2.schema.support.H2StringType;
import org.dbdatasync.connector.h2.schema.support.H2TimestampType;
import org.dbdatasync.sdk.schema.AbstractDatabaseSchemaResolver;
import org.dbdatasync.sdk.schema.DataType;

import java.util.Map;
import java.util.stream.Stream;

/**
 * H2 类型映射
 */
public final class H2SchemaResolver extends AbstractDatabaseSchemaResolver {

    @Override
    protected void initDataTypeMapping(Map<String, DataType> mapping) {
        Stream.of(new H2StringType(), new H2IntType(), new H2LongType(), new H2DoubleType(), new H2BytesType(), new H2TimestampType(), new H2BooleanType()).forEach(t->t.getSupportedTypeName().forEach(typeName-> {
            if (mapping.containsKey(typeName)) {
                throw new H2Exception("Duplicate type name: " + typeName);
            }
            mapping.put(typeName, t);
        }));
    }
}
