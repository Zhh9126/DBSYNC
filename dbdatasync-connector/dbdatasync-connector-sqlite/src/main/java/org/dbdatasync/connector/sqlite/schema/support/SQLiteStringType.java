/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlite.schema.support;

import org.dbdatasync.common.util.DateFormatUtil;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.StringType;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2026-01-11 22:21
 *
 * 字符与时间族类型（SQLite 无独立日期时间类型，按 TEXT 亲和存储）。
 * 覆盖 TEXT/VARCHAR/CHAR/CLOB/DATE/TIME/DATETIME/TIMESTAMP。
 */
public final class SQLiteStringType extends StringType {

    private enum TypeEnum {

        TEXT("TEXT"),
        VARCHAR("VARCHAR"),
        CHAR("CHAR"),
        CLOB("CLOB"),
        DATE("DATE"),
        TIME("TIME"),
        DATETIME("DATETIME"),
        TIMESTAMP("TIMESTAMP");

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
}
