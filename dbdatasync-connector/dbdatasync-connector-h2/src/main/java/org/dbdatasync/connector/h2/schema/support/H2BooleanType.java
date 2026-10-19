/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.h2.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.BooleanType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * H2 布尔类型（BOOLEAN/BOOL）。实现 merge：Boolean/Number/String 均可写入。
 */
public final class H2BooleanType extends BooleanType {

    private enum TypeEnum {

        BOOLEAN("BOOLEAN"),
        BOOL("BOOL");

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
    protected Boolean merge(Object val, Field field) {
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        if (val instanceof Number) {
            return ((Number) val).doubleValue() != 0.0D;
        }
        if (val instanceof String) {
            String text = ((String) val).trim();
            if (text.equalsIgnoreCase("true") || text.equals("1")) {
                return Boolean.TRUE;
            }
            if (text.equalsIgnoreCase("false") || text.equals("0")) {
                return Boolean.FALSE;
            }
            try {
                return new BigDecimal(text).doubleValue() != 0.0D;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return throwUnsupportedException(val, field);
    }
}
