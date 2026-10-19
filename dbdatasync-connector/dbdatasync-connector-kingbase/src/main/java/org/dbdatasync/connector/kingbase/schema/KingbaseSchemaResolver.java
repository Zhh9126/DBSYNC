/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.kingbase.schema;

import com.google.protobuf.ByteString;
import com.kingbase8.geometric.KBpoint;
import com.kingbase8.util.KBobject;
import org.dbdatasync.connector.kingbase.KingbaseException;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseSQLBooleanType;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseSQLBytesType;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseSQLDateType;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseSQLDecimalType;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseSQLDoubleType;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseSQLFloatType;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseSQLIntType;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseSQLLongType;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseSQLTimeType;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseSQLTimestampType;
import org.dbdatasync.connector.kingbase.schema.support.KingbaseStringType;
import org.dbdatasync.connector.postgresql.schema.PostgreSQLSchemaResolver;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.DataType;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-06 18:30
 */
public final class KingbaseSchemaResolver extends PostgreSQLSchemaResolver {

    /**
     * JDBC 元数据返回的常见类型别名 → 注册类型名归一表。
     * <p>KingbaseES 驱动 {@code getColumns().TYPE_NAME} 对 {@code int4/int8} 列常返回
     * {@code int/bigint/smallint}（Oracle 风格），而注册表仅含 PG 内部名（int4/int8 等），
     * 若不归一会导致 {@code does not support type ... merge into [int]}。
     */
    private static final String[][] TYPE_ALIASES = {
            {"int", "int4"}, {"integer", "int4"}, {"serial4", "int4"},
            {"bigint", "int8"}, {"serial8", "int8"},
            {"smallint", "int2"}, {"serial2", "int2"},
            {"number", "numeric"}, {"dec", "numeric"}, {"decimal", "numeric"},
            {"real", "float4"},
            {"double precision", "float8"},
            {"datetime", "timestamp"}, {"timestamp without time zone", "timestamp"},
            {"timestamp with time zone", "timestamptz"},
            {"time without time zone", "time"},
            {"time with time zone", "timetz"},
            {"boolean", "bool"},
            {"character varying", "varchar"},
            {"character", "bpchar"},
            {"varchar2", "varchar"}, {"blob", "bytea"}, {"raw", "bytea"}, {"raw(2000)", "bytea"},
            {"smallint identity", "int2"}, {"int identity", "int4"}, {"bigint identity", "int8"},
    };

    @Override
    protected void initDataTypeMapping(Map<String, DataType> mapping) {
        Stream.of(new KingbaseStringType(), new KingbaseSQLTimeType(), new KingbaseSQLTimestampType(), new KingbaseSQLLongType(),
                        new KingbaseSQLIntType(), new KingbaseSQLFloatType(), new KingbaseSQLDoubleType(),
                        new KingbaseSQLDecimalType(), new KingbaseSQLDateType(), new KingbaseSQLBytesType(), new KingbaseSQLBooleanType())
                .forEach(t -> t.getSupportedTypeName().forEach(typeName -> {
                    if (mapping.containsKey(typeName)) {
                        throw new KingbaseException("Duplicate type name: " + typeName);
                    }
                    mapping.put(typeName, t);
                }));
        // 别名归一：仅补充缺失的映射，同族类型共享实例
        for (String[] alias : TYPE_ALIASES) {
            DataType type = mapping.get(alias[1]);
            if (type != null && !mapping.containsKey(alias[0])) {
                mapping.put(alias[0], type);
            }
        }
    }

    @Override
    public ByteString serialize(Object value, Field field) {
        String type = value.getClass().getName();
        switch (type) {
            case "com.kingbase8.util.KBobject":
                KBobject kbObject = (KBobject) value;
                return ByteString.copyFromUtf8(Objects.requireNonNull(kbObject.getValue()));
            case "com.kingbase8.geometric.KBpoint":
                KBpoint kbPoint = (KBpoint) value;
                return ByteString.copyFromUtf8(Objects.requireNonNull(kbPoint.getValue()));
            default:
                break;
        }
        return super.serialize(value, field);
    }
}
