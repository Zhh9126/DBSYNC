/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.sdk.schema.support;

import org.dbdatasync.sdk.enums.DataTypeEnum;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.AbstractDataType;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2024-11-21 23:56
 */
public abstract class FloatType extends AbstractDataType<Float> {

    @Override
    public DataTypeEnum getType() {
        return DataTypeEnum.FLOAT;
    }

    @Override
    protected Object convert(Object val, Field field) {
        if (val instanceof Number) {
            return ((Number) val).floatValue();
        }
        if (val instanceof String) {
            return Float.parseFloat((String) val);
        }
        return throwUnsupportedException(val, field);
    }
}
