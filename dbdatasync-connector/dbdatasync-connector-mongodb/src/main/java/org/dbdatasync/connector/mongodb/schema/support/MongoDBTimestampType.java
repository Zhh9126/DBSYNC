/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.mongodb.schema.support;

import org.dbdatasync.common.util.DateFormatUtil;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.TimestampType;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.Set;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-06 20:00
 */
public final class MongoDBTimestampType extends TimestampType {

    @Override
    public Set<String> getSupportedTypeName() {
        // bson "date" 为毫秒精度日期时间；同时注册 datetime/timestamp 语义别名，
        // 供自定义表列定义（如 INT/STRING/TIMESTAMP 惯例）与类型预检归类使用
        return newHashSet("date", "datetime", "timestamp");
    }

    private static Set<String> newHashSet(String... names) {
        return new java.util.HashSet<>(java.util.Arrays.asList(names));
    }

    @Override
    protected Timestamp merge(Object val, Field field) {
        if (val instanceof String) {
            return DateFormatUtil.stringToTimestamp((String) val);
        }
        if (val instanceof Timestamp) {
            return (Timestamp) val;
        }
        if (val instanceof Date) {
            return new Timestamp(((Date) val).getTime());
        }
        if (val instanceof java.util.Date) {
            return new Timestamp(((java.util.Date) val).getTime());
        }
        if (val instanceof Long) {
            return new Timestamp((Long) val);
        }
        return throwUnsupportedException(val, field);
    }

    @Override
    protected Object convert(Object val, Field field) {
        if (val instanceof Timestamp) {
            return new java.util.Date(((Timestamp) val).getTime());
        }
        if (val instanceof Date) {
            return new java.util.Date(((Date) val).getTime());
        }
        if (val instanceof java.util.Date) {
            return val;
        }
        if (val instanceof Long) {
            return new java.util.Date((Long) val);
        }
        if (val instanceof String) {
            return new java.util.Date(DateFormatUtil.stringToTimestamp((String) val).getTime());
        }
        return throwUnsupportedException(val, field);
    }
}
