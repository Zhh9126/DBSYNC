/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.sdk.connector.database;

import org.dbdatasync.sdk.SdkException;
import org.dbdatasync.sdk.config.SqlBuilderConfig;
import org.dbdatasync.sdk.connector.database.sqlbuilder.SqlBuilder;

public abstract class AbstractSqlBuilder implements SqlBuilder {

    @Override
    public String buildQuerySql(SqlBuilderConfig config) {
        throw new SdkException("Not implemented");
    }
}
