package org.dbdatasync.biz.precheck;

import org.dbdatasync.common.util.JsonUtil;

/**
 * 迁移/同步预检测问题项（产品化：执行前检测，问题闭环展示给用户修复）
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-10-17
 */
public class PreCheckIssue {

    /** 级别：ERROR 阻断执行 / WARN 可以执行但需人工确认 */
    public static final String LEVEL_ERROR = "error";
    public static final String LEVEL_WARN = "warn";

    /** 级别 */
    private String level;

    /** 规则编号，如 PC101 */
    private String code;

    /** 规则说明，如 字段类型不兼容 */
    private String rule;

    /** 检查范围：mapping / database-sync 下的库映射序号+表映射 */
    private String table;

    /** 字段名 */
    private String column;

    /** 源端类型 */
    private String sourceType;

    /** 目标端类型 */
    private String targetType;

    /** 问题描述 */
    private String message;

    /** 修复建议（可引用系统内置转换函数 ConvertEnum，或人工调整目标 DDL） */
    private String suggestion;

    public PreCheckIssue() {
    }

    public PreCheckIssue(String level, String code, String rule, String table, String column,
                         String sourceType, String targetType, String message, String suggestion) {
        this.level = level;
        this.code = code;
        this.rule = rule;
        this.table = table;
        this.column = column;
        this.sourceType = sourceType;
        this.targetType = targetType;
        this.message = message;
        this.suggestion = suggestion;
    }

    public static PreCheckIssue error(String code, String rule, String table, String column,
                                      String sourceType, String targetType, String message, String suggestion) {
        return new PreCheckIssue(LEVEL_ERROR, code, rule, table, column, sourceType, targetType, message, suggestion);
    }

    public static PreCheckIssue warn(String code, String rule, String table, String column,
                                     String sourceType, String targetType, String message, String suggestion) {
        return new PreCheckIssue(LEVEL_WARN, code, rule, table, column, sourceType, targetType, message, suggestion);
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    @Override
    public String toString() {
        return JsonUtil.objToJson(this);
    }
}
