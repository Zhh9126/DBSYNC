/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.h2.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.DoubleType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * H2 数值类型（DOUBLE/DOUBLE PRECISION/REAL/FLOAT/NUMERIC/DECIMAL/NUMBER）。
 * 补充 NUMERIC/DECIMAL 族名，并实现 merge，接收源端 BigDecimal 等数值。
 */
public final class H2DoubleType extends DoubleType {

    private enum TypeEnum {

        DOUBLE("DOUBLE"),
        DOUBLE_PRECISION("DOUBLE PRECISION"),
        REAL("REAL"),
        FLOAT("FLOAT"),
        NUMERIC("NUMERIC"),
        DECIMAL("DECIMAL"),
        NUMBER("NUMBER");

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
    protected Double merge(Object val, Field field) {
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        if (val instanceof Boolean) {
            return (Boolean) val ? 1.0D : 0.0D;
        }
        if (val instanceof String) {
            try {
                return new BigDecimal(((String) val).trim()).doubleValue();
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return throwUnsupportedException(val, field);
    }
}
