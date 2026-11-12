package org.dbdatasync.biz.sqlscan;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 兼容度扫描结果：分级统计 + 问题清单。
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
public class SqlScanResult {

    /** 源方言 */
    private String sourceDialect;

    /** 目标方言 */
    private String targetDialect;

    /** 是否顺利解析并完成扫描（false=整体解析失败，仅能给提示） */
    private boolean scanned;

    /** 扫描的语句总数 */
    private int statementCount;

    /** 可扫描文本字符数 */
    private int sqlLength;

    /** error / warn / info 计数 */
    private int errorCount;
    private int warnCount;
    private int infoCount;

    /** 问题清单 */
    private List<SqlCompatibilityIssue> issues = new ArrayList<>();

    public String getSourceDialect() {
        return sourceDialect;
    }

    public void setSourceDialect(String sourceDialect) {
        this.sourceDialect = sourceDialect;
    }

    public String getTargetDialect() {
        return targetDialect;
    }

    public void setTargetDialect(String targetDialect) {
        this.targetDialect = targetDialect;
    }

    public boolean isScanned() {
        return scanned;
    }

    public void setScanned(boolean scanned) {
        this.scanned = scanned;
    }

    public int getStatementCount() {
        return statementCount;
    }

    public void setStatementCount(int statementCount) {
        this.statementCount = statementCount;
    }

    public int getSqlLength() {
        return sqlLength;
    }

    public void setSqlLength(int sqlLength) {
        this.sqlLength = sqlLength;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public int getWarnCount() {
        return warnCount;
    }

    public void setWarnCount(int warnCount) {
        this.warnCount = warnCount;
    }

    public int getInfoCount() {
        return infoCount;
    }

    public void setInfoCount(int infoCount) {
        this.infoCount = infoCount;
    }

    public List<SqlCompatibilityIssue> getIssues() {
        return issues;
    }

    public void setIssues(List<SqlCompatibilityIssue> issues) {
        this.issues = issues;
    }

    public void addIssue(SqlCompatibilityIssue issue) {
        this.issues.add(issue);
        switch (issue.getLevel()) {
            case SqlCompatibilityIssue.LEVEL_ERROR:
                this.errorCount++;
                break;
            case SqlCompatibilityIssue.LEVEL_WARN:
                this.warnCount++;
                break;
            default:
                this.infoCount++;
                break;
        }
    }
}
