/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.oceanbase.schema;

import org.dbdatasync.connector.oceanbase.OceanBaseException;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseByteType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseBytesType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseDateType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseDecimalType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseDoubleType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseFloatType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseIntType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseLongType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseShortType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseStringType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseTimeType;
import org.dbdatasync.connector.oceanbase.schema.support.OceanBaseTimestampType;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.AbstractDatabaseSchemaResolver;
import org.dbdatasync.sdk.schema.DataType;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * OceanBase标准数据类型解析器
 *
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-04 00:20
 */
public final class OceanBaseSchemaResolver extends AbstractDatabaseSchemaResolver {

    /**
     * 规范化类型名：转大写
     */
    public static String normalizeTypeName(String typeName) {
        return typeName.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    protected DataType getDataType(Map<String, DataType> mapping, Field field) {
        DataType dataType = mapping.get(field.getTypeName());
        if (dataType == null) {
            dataType = mapping.get(normalizeTypeName(field.getTypeName()));
        }
        return dataType;
    }

    @Override
    protected void initDataTypeMapping(Map<String, DataType> mapping) {
        Stream.of(
                        new OceanBaseBytesType(),
                        new OceanBaseByteType(),
                        new OceanBaseDateType(),
                        new OceanBaseDecimalType(),
                        new OceanBaseDoubleType(),
                        new OceanBaseFloatType(),
                        new OceanBaseIntType(),
                        new OceanBaseLongType(),
                        new OceanBaseShortType(),
                        new OceanBaseStringType(),
                        new OceanBaseTimestampType(),
                        new OceanBaseTimeType())
                .forEach(t -> t.getSupportedTypeName().forEach(typeName -> {
                    String key = normalizeTypeName(typeName);
                    if (mapping.containsKey(key)) {
                        throw new OceanBaseException("Duplicate type name: " + key);
                    }
                    mapping.put(key, t);
                }));
    }

}