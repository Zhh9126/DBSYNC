/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.h2.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.LongType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * H2 BIGINT 类型。实现 merge：Number/Boolean/String 数值均可写入。
 */
public final class H2LongType extends LongType {

    private enum TypeEnum {

        BIGINT("BIGINT");

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
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        if (val instanceof Boolean) {
            return (Boolean) val ? 1L : 0L;
        }
        if (val instanceof String) {
            try {
                return new BigDecimal(((String) val).trim()).longValue();
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return throwUnsupportedException(val, field);
    }
}
