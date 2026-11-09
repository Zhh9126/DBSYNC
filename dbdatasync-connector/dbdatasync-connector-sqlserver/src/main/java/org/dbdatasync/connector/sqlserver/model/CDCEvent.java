/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.connector.sqlserver.model;

import java.util.List;

public final class CDCEvent {

    private String tableName;
    private int code;
    private List<Object> row;

    /** Wave24：cdc.lsn_time_mapping.tran_end_time，源端事务提交时间（批次粒度，epoch millis） */
    private long sourceTimeMillis;

    public CDCEvent(String tableName, int code, List<Object> row) {
        this(tableName, code, row, 0L);
    }

    public CDCEvent(String tableName, int code, List<Object> row, long sourceTimeMillis) {
        this.tableName = tableName;
        this.code = code;
        this.row = row;
        this.sourceTimeMillis = sourceTimeMillis;
    }

    public long getSourceTimeMillis() {
        return sourceTimeMillis;
    }

    public String getTableName() {
        return tableName;
    }

    public int getCode() {
        return code;
    }

    public List<Object> getRow() {
        return row;
    }
}
