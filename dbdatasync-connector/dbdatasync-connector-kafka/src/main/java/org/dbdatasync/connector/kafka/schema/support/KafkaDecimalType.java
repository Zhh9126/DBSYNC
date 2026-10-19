/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.kafka.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.DecimalType;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2026-01-11 22:21
 */
public final class KafkaDecimalType extends DecimalType {

    @Override
    protected BigDecimal merge(Object val, Field field) {
        if (val instanceof String) {
            return new BigDecimal((String) val);
        }
        if (val instanceof Number) {
            return new BigDecimal(val.toString());
        }
        if (val instanceof Boolean) {
            return ((Boolean) val) ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        return throwUnsupportedException(val, field);
    }

    @Override
    public Set<String> getSupportedTypeName() {
        Set<String> types = new HashSet<>();
        types.add(getType().name());
        return types;
    }
}
