/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.mongodb.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.BooleanType;

import java.util.Collections;
import java.util.Set;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-06 20:00
 */
public final class MongoDBBooleanType extends BooleanType {

    @Override
    public Set<String> getSupportedTypeName() {
        return Collections.singleton("bool");
    }

    @Override
    protected Boolean merge(Object val, Field field) {
        if (val instanceof String) {
            String str = (String) val;
            return "true".equalsIgnoreCase(str) || "1".equals(str);
        }
        if (val instanceof Number) {
            return ((Number) val).intValue() != 0;
        }
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        return throwUnsupportedException(val, field);
    }
}
