/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlite.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.IntType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2026-01-11 22:21
 *
 * 整数族类型（SQLite 动态类型，按 INTEGER 亲和 存储）。
 * 覆盖 INTEGER/INT/BIGINT/TINYINT/SMALLINT/MEDIUMINT/BOOLEAN，并实现 merge。
 */
public final class SQLiteIntType extends IntType {

    private enum TypeEnum {

        INTEGER("INTEGER"),
        INT("INT"),
        BIGINT("BIGINT"),
        TINYINT("TINYINT"),
        SMALLINT("SMALLINT"),
        MEDIUMINT("MEDIUMINT"),
        BOOLEAN("BOOLEAN");

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
    protected Integer merge(Object val, Field field) {
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val instanceof Boolean) {
            return (Boolean) val ? 1 : 0;
        }
        if (val instanceof String) {
            try {
                return new BigDecimal(((String) val).trim()).intValue();
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return throwUnsupportedException(val, field);
    }
}
