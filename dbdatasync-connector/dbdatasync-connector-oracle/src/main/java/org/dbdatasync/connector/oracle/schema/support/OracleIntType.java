/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.connector.oracle.schema.support;

import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.support.IntType;

import java.util.Collections;
import java.util.Set;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2024-12-25 00:03
 */
public final class OracleIntType extends IntType {

    @Override
    public Set<String> getSupportedTypeName() {
        return Collections.emptySet();
    }

    @Override
    protected Integer merge(Object val, Field field) {
        return throwUnsupportedException(val, field);
    }
}
