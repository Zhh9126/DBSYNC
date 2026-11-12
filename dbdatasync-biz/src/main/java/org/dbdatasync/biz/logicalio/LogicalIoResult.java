package org.dbdatasync.biz.logicalio;

/**
 * 逻辑导入导出结果统计载体。
 *
 * <p>汇总一次逻辑导出 / 导入操作的关键计量指标，
 * 供上层（任务、监控）统一展示与上报。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class LogicalIoResult {

    /** 导出的行数。 */
    private long exportedRows;

    /** 导出的字节数（按 UTF-8 计）。 */
    private long exportedBytes;

    /** 生成的 INSERT 语句条数。 */
    private long statementCount;

    /** 导入的行数。 */
    private long importedRows;

    /**
     * 默认构造方法。
     */
    public LogicalIoResult() {
    }

    /**
     * 获取导出的行数。
     *
     * @return 导出行数
     */
    public long getExportedRows() {
        return exportedRows;
    }

    /**
     * 设置导出的行数。
     *
     * @param exportedRows 导出行数
     */
    public void setExportedRows(long exportedRows) {
        this.exportedRows = exportedRows;
    }

    /**
     * 获取导出的字节数。
     *
     * @return 导出字节数（UTF-8）
     */
    public long getExportedBytes() {
        return exportedBytes;
    }

    /**
     * 设置导出的字节数。
     *
     * @param exportedBytes 导出字节数
     */
    public void setExportedBytes(long exportedBytes) {
        this.exportedBytes = exportedBytes;
    }

    /**
     * 获取生成的 INSERT 语句条数。
     *
     * @return 语句条数
     */
    public long getStatementCount() {
        return statementCount;
    }

    /**
     * 设置生成的 INSERT 语句条数。
     *
     * @param statementCount 语句条数
     */
    public void setStatementCount(long statementCount) {
        this.statementCount = statementCount;
    }

    /**
     * 获取导入的行数。
     *
     * @return 导入行数
     */
    public long getImportedRows() {
        return importedRows;
    }

    /**
     * 设置导入的行数。
     *
     * @param importedRows 导入行数
     */
    public void setImportedRows(long importedRows) {
        this.importedRows = importedRows;
    }
}
