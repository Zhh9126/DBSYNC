/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.h2.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.TimestampType;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * H2 时间类型（TIMESTAMP/DATETIME/TIMESTAMP WITH TIME ZONE/DATE/TIME）。
 * 实现 merge：Timestamp/日期类型/字符串均可写入。
 */
public final class H2TimestampType extends TimestampType {

    private enum TypeEnum {

        TIMESTAMP("TIMESTAMP"),
        DATETIME("DATETIME"),
        TIMESTAMP_WITH_TIME_ZONE("TIMESTAMP WITH TIME ZONE"),
        TIMESTAMP_WITH_LOCAL_TIME_ZONE("TIMESTAMP WITH LOCAL TIME ZONE"),
        DATE("DATE"),
        TIME("TIME"),
        TIME_WITH_TIME_ZONE("TIME WITH TIME ZONE");

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
    protected Timestamp merge(Object val, Field field) {
        if (val instanceof Timestamp) {
            return (Timestamp) val;
        }
        if (val instanceof LocalDateTime) {
            return Timestamp.valueOf((LocalDateTime) val);
        }
        if (val instanceof OffsetDateTime) {
            return Timestamp.from(((OffsetDateTime) val).toInstant());
        }
        if (val instanceof java.util.Date) {
            return new Timestamp(((java.util.Date) val).getTime());
        }
        if (val instanceof String) {
            String text = ((String) val).trim();
            try {
                return Timestamp.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                try {
                    return Timestamp.valueOf(LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                } catch (Exception ignored2) {
                    // fall through
                }
            }
        }
        return throwUnsupportedException(val, field);
    }
}
