/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlite.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.DoubleType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2026-01-11 22:21
 *
 * 数值族类型（SQLite 动态类型，全部按 REAL 亲和存储）。
 * 覆盖 REAL/FLOAT/DOUBLE/NUMERIC/DECIMAL/DEC/NUMBER，
 * 并实现 merge：目标列声明为 DECIMAL/NUMERIC 时接收源端 BigDecimal 等数值。
 */
public final class SQLiteDoubleType extends DoubleType {

    private enum TypeEnum {

        REAL("REAL"),
        FLOAT("FLOAT"),
        DOUBLE("DOUBLE"),
        NUMERIC("NUMERIC"),
        DECIMAL("DECIMAL"),
        DEC("DEC"),
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
