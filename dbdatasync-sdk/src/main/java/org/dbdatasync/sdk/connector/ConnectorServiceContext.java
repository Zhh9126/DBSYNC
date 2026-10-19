/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.sdk.connector;

import org.dbdatasync.sdk.model.Table;

import java.util.List;

/**
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2025-10-25 16:32
 */
public interface ConnectorServiceContext {

    String getCatalog();

    String getSchema();

    List<Table> getTablePatterns();
}
