/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlite.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.BytesType;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2026-01-11 22:21
 *
 * 二进制类型，覆盖 BLOB，并实现 merge（byte[]/ByteBuffer/String）。
 */
public final class SQLiteBytesType extends BytesType {

    private enum TypeEnum {

        BLOB("BLOB");

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
    protected byte[] merge(Object val, Field field) {
        if (val instanceof byte[]) {
            return (byte[]) val;
        }
        if (val instanceof ByteBuffer) {
            ByteBuffer buffer = (ByteBuffer) val;
            byte[] bytes = new byte[buffer.remaining() - buffer.position()];
            buffer.get(bytes);
            return bytes;
        }
        if (val instanceof String) {
            return ((String) val).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return throwUnsupportedException(val, field);
    }
}
