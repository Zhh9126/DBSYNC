package org.dbdatasync.biz.vo;

import org.dbdatasync.biz.sqlscan.SqlScanResult;

/**
 * 应用 SQL 兼容度预检报告（Wave10 P1-3）。
 *
 * <p>包装既有 {@link SqlScanResult}（scanned / statementCount / errorCount / warnCount /
 * infoCount / issues），附输入模式与固定提示。<strong>仅提示、不阻断任务创建</strong>：
 * {@code blocking} 固定为 {@code false}，不写入任务、不改变 {@code add()} 主流程。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class SqlPrecheckReportVO {

    /** 扫描结果（未输入时为 null，表示零副作用、未调用扫描器） */
    private SqlScanResult result;

    /** 输入模式：none / text / directory / mixed */
    private String inputMode;

    /** 输入摘要（含目录越界/不存在等可读提示） */
    private String inputSummary;

    /** 固定提示语 */
    private String hint;

    /** 是否阻断任务创建：固定 false，本轮不新增阻断开关 */
    private boolean blocking = false;

    public SqlScanResult getResult() {
        return result;
    }

    public void setResult(SqlScanResult result) {
        this.result = result;
    }

    public String getInputMode() {
        return inputMode;
    }

    public void setInputMode(String inputMode) {
        this.inputMode = inputMode;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public void setInputSummary(String inputSummary) {
        this.inputSummary = inputSummary;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
    }
}
