/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.sdk.enums;

/**
 * 支持同步的DDL命令
 *
 * @version 1.0.0
 * @Author life
 * @Date 2023-09-24 14:24
 */
public enum DDLOperationEnum {
    /**
     * 变更字段属性
     */
    ALTER_MODIFY,
    /**
     * 新增字段
     */
    ALTER_ADD,
    /**
     * 变更字段名称
     */
    ALTER_CHANGE,
    /**
     * 删除字段
     */
    ALTER_DROP,
    /** 建表（表级，需人工确认） */
    CREATE_TABLE,
    /** 删表（表级，需人工确认） */
    DROP_TABLE,
    /** 清空表（表级，需人工确认） */
    TRUNCATE_TABLE,
    /** 表重命名（表级，需人工确认） */
    RENAME_TABLE,
    /** 建索引 */
    CREATE_INDEX,
    /** 删索引 */
    DROP_INDEX,
    /** 加约束 */
    ADD_CONSTRAINT,
    /** 删约束 */
    DROP_CONSTRAINT,
    /** 未识别 */
    UNKNOWN;
}
