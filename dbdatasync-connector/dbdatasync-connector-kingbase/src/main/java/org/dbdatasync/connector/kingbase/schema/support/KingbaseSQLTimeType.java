/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.kingbase.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.TimeType;

import java.sql.Time;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Author 绌夸簯
 * @Version 1.0.0
 * @Date 2026-01-11 22:21
 */
public final class KingbaseSQLTimeType extends TimeType {

    private enum TypeEnum {

        TIME("time");

        private final String value;

        TypeEnum(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    @Override
    public Set<String> getSupportedTypeName() {
        return Arrays.stream(TypeEnum.values()).map(TypeEnum::getValue).collect(Collectors.toSet());
    }

    @Override
    protected Time merge(Object val, Field field) {
        return (Time) convert(val, field);
    }

    @Override
    protected Object convert(Object val, Field field) {
       return super.convert(val,field);
    }
}
