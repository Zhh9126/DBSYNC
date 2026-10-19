/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.engine;

import java.util.List;
import java.util.Map;

/**
 * 数据记录载体（Channel 中流转的最小单元）。
 */
public class Record {

    /** 列数据（有序，与 schema 对齐） */
    private final List<Object> columns;

    /** 原始行（保留字段名映射，便于写端适配） */
    private final Map<String, Object> row;

    /** 本次记录在源端的游标值（用于断点续传） */
    private Object cursorValue;

    public Record(List<Object> columns, Map<String, Object> row) {
        this.columns = columns;
        this.row = row;
    }

    public List<Object> getColumns() { return columns; }
    public Map<String, Object> getRow() { return row; }
    public Object getCursorValue() { return cursorValue; }
    public void setCursorValue(Object cursorValue) { this.cursorValue = cursorValue; }

    /** 估算记录字节数（用于限速） */
    public long estimateBytes() {
        long size = 0;
        for (Object c : columns) {
            if (c == null) continue;
            size += c.toString().length();
        }
        return size;
    }
}
