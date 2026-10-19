/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.duckdb.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.LongType;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DuckDB 长整型
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-07-16 10:00
 */
public final class DuckDBLongType extends LongType {

    private enum TypeEnum {
        BIGINT("BIGINT"),
        UBIGINT("UBIGINT");

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
    protected Long merge(Object val, Field field) {
        return throwUnsupportedException(val, field);
    }
}
