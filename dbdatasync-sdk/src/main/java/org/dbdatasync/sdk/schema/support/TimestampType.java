/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.sdk.schema.support;

import org.dbdatasync.common.util.DateFormatUtil;
import org.dbdatasync.sdk.enums.DataTypeEnum;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.AbstractDataType;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2024-11-21 23:56
 */
public abstract class TimestampType extends AbstractDataType<Timestamp> {

    @Override
    public DataTypeEnum getType() {
        return DataTypeEnum.TIMESTAMP;
    }

    @Override
    protected Object convert(Object val, Field field) {
        if (val instanceof Timestamp) {
            return val;
        }
        if (val instanceof String) {
            Timestamp timestamp = DateFormatUtil.stringToTimestamp((String) val);
            if (null != timestamp) {
                return timestamp;
            }
        }
        if (val instanceof Date) {
            Date date = (Date) val;
            return new Timestamp(date.getTime());
        }

        if (val instanceof LocalDateTime) {
            LocalDateTime dateTime = (LocalDateTime) val;
            return Timestamp.valueOf(dateTime);
        }

        if (val instanceof byte[]) {
            byte[] bytes = (byte[]) val;
            String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            Timestamp timestamp = DateFormatUtil.stringToTimestamp(s);
            if (null != timestamp) {
                return timestamp;
            }
        }

        if (val instanceof java.util.Date) {
            java.util.Date date = (java.util.Date) val;
            return new Timestamp(date.getTime());
        }

        if (val instanceof OffsetDateTime) {
            OffsetDateTime date = (OffsetDateTime) val;
            return Timestamp.from(date.toInstant());
        }
        if (val instanceof Number) {
            return new Timestamp(((Number) val).longValue());
        }
        return throwUnsupportedException(val, field);
    }
}
