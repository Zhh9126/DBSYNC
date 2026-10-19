/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.doris.schema.support;

import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.StringType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2026-06-06 23:50
 */
public final class DorisStringType extends StringType {

    private enum TypeEnum {
        STRING, VARCHAR, JSON, HLL, BITMAP, ARRAY, MAP, STRUCT, IPV4, IPV6, VARIANT, AGG_STATE, QUANTILE_STATE;

        public String getValue() {
            return name();
        }
    }

    @Override
    public Set<String> getSupportedTypeName() {
        return Arrays.stream(TypeEnum.values()).map(TypeEnum::getValue).collect(Collectors.toSet());
    }

    @Override
    protected String merge(Object val, Field field) {
        if (val == null) {
            return null;
        }
        if (val instanceof String) {
            return (String) val;
        }
        if (val instanceof byte[]) {
            // MySQL BLOB 映射到 Doris STRING/VARCHAR 时按 Latin1 保真，便于与源 byte[] 语义比较
            return new String((byte[]) val, StandardCharsets.ISO_8859_1);
        }
        return JsonUtil.objToJsonSafe(val);
    }

    @Override
    protected Object convert(Object val, Field field) {
        if (val instanceof String) {
            return val;
        }
        if (val instanceof byte[]) {
            return new String((byte[]) val, StandardCharsets.ISO_8859_1);
        }
        return JsonUtil.objToJsonSafe(val);
    }
}
