/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlite.schema;

import org.dbdatasync.connector.sqlite.SQLiteException;
import org.dbdatasync.connector.sqlite.schema.support.SQLiteBytesType;
import org.dbdatasync.connector.sqlite.schema.support.SQLiteDoubleType;
import org.dbdatasync.connector.sqlite.schema.support.SQLiteIntType;
import org.dbdatasync.connector.sqlite.schema.support.SQLiteStringType;
import org.dbdatasync.sdk.schema.AbstractDatabaseSchemaResolver;
import org.dbdatasync.sdk.schema.DataType;

import java.util.Map;
import java.util.stream.Stream;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-01-13 00:08
 */
public final class SQLiteSchemaResolver extends AbstractDatabaseSchemaResolver {

    @Override
    protected void initDataTypeMapping(Map<String, DataType> mapping) {
        Stream.of(new SQLiteStringType(), new SQLiteIntType(), new SQLiteDoubleType(), new SQLiteBytesType()).forEach(t->t.getSupportedTypeName().forEach(typeName-> {
            if (mapping.containsKey(typeName)) {
                throw new SQLiteException("Duplicate type name: " + typeName);
            }
            mapping.put(typeName, t);
        }));
    }
}