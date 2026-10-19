/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.sdk.schema;

import org.dbdatasync.sdk.enums.DataTypeEnum;
import org.dbdatasync.sdk.model.Field;

import java.util.Set;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2024-11-23 23:00
 */
public interface DataType {

    /**
     * 获取支持的转换类型
     */
    Set<String> getSupportedTypeName();

    /**
     * 转换为标准数据类型
     *
     * @param val   转换值
     * @param field 数据类型
     * @return Object
     */
    Object mergeValue(Object val, Field field);

    /**
     * 转换为指定数据类型
     *
     * @param val   转换值
     * @param field 数据类型
     * @return Object
     */
    Object convertValue(Object val, Field field);

    /**
     * 获取数据类型
     */
    DataTypeEnum getType();
}
