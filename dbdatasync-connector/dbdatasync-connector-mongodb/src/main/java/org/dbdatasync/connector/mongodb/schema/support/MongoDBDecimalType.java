/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.mongodb.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.DecimalType;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Set;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-06 20:00
 */
public final class MongoDBDecimalType extends DecimalType {

    @Override
    public Set<String> getSupportedTypeName() {
        return Collections.singleton("decimal");
    }

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
}
