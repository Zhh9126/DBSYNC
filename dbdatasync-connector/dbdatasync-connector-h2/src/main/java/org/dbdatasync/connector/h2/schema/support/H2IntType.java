/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.h2.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.IntType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * H2 整型（INT/INTEGER/SMALLINT/TINYINT）。实现 merge：Number/Boolean/String 数值均可写入。
 */
public final class H2IntType extends IntType {

    private enum TypeEnum {

        INT("INT"),
        INTEGER("INTEGER"),
        SMALLINT("SMALLINT"),
        TINYINT("TINYINT");

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
