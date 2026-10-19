/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.sdk.connector.database;

public interface HandleCallback {

    Object apply(DatabaseTemplate databaseTemplate) throws Exception;
}
