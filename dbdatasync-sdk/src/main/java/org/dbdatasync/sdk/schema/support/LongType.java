/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.sdk.schema.support;

import org.dbdatasync.common.util.NumberUtil;
import org.dbdatasync.sdk.enums.DataTypeEnum;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.AbstractDataType;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2024-11-21 23:56
 */
public abstract class LongType extends AbstractDataType<Long> {

    @Override
    public DataTypeEnum getType() {
        return DataTypeEnum.LONG;
    }

    @Override
    protected Object convert(Object val, Field field) {
        if (val instanceof Number) {
            Number num = (Number) val;
            return num.longValue();
        }
        if (val instanceof String) {
            return NumberUtil.toLong((String) val);
        }
        if (val instanceof Boolean) {
            Boolean b = (Boolean) val;
            return (long) (b ? 1 : 0);
        }
        return throwUnsupportedException(val, field);
    }
}
