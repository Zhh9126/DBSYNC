/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.redis.schema.support;

import org.dbdatasync.common.util.DateFormatUtil;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.StringType;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2026-01-11 22:21
 */
public final class RedisStringType extends StringType {

    @Override
    protected String merge(Object val, Field field) {
        if (val instanceof byte[]) {
            return new String((byte[]) val);
        }

        if (val instanceof Number) {
            Number number = (Number) val;
            return number.toString();
        }

        if (val instanceof Timestamp) {
            return DateFormatUtil.timestampToString((Timestamp) val);
        }

        if (val instanceof Date) {
            return DateFormatUtil.dateToString((Date) val);
        }

        if (val instanceof java.util.Date) {
            return DateFormatUtil.dateToString((java.util.Date) val);
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
