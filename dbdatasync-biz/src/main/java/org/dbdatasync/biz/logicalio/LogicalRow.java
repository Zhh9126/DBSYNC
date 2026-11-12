package org.dbdatasync.biz.logicalio;

import java.util.List;

/**
 * 逻辑导入导出模块的行数据载体。
 *
 * <p>用于「行数据 ↔ 文本格式（CSV / INSERT SQL）」的纯转换场景，
 * 本类只承载列名与对应值，不做任何数据库（JDBC）操作。
 * 真正的库端读写由调用方（连接器 reader/writer）完成。</p>
 *
 * <p>约定：{@code columns} 与 {@code values} 按下标一一对应，
 * 即 {@code values.get(i)} 是 {@code columns.get(i)} 对应的值。
 * 所有值统一以字符串表示（简化口径）。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class LogicalRow {

    /** 列名列表，与 {@link #values} 按下标一一对应。 */
    private List<String> columns;

    /** 列值列表（统一按字符串表示），与 {@link #columns} 按下标一一对应。 */
    private List<String> values;

    /**
     * 默认构造方法。
     */
    public LogicalRow() {
    }

    /**
     * 全参构造方法。
     *
     * @param columns 列名列表
     * @param values  列值列表（字符串表示）
     */
    public LogicalRow(List<String> columns, List<String> values) {
        this.columns = columns;
        this.values = values;
    }

    /**
     * 获取列名列表。
     *
     * @return 列名列表
     */
    public List<String> getColumns() {
        return columns;
    }

    /**
     * 设置列名列表。
     *
     * @param columns 列名列表
     */
    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    /**
     * 获取列值列表。
     *
     * @return 列值列表
     */
    public List<String> getValues() {
        return values;
    }

    /**
     * 设置列值列表。
     *
     * @param values 列值列表
     */
    public void setValues(List<String> values) {
        this.values = values;
    }

    /**
     * 按列名取值。
     *
     * @param col 列名
     * @return 该列对应的值；列不存在时返回 {@code null}
     * @throws IllegalStateException 若本行尚未设置列名列表
     */
    public String getValue(String col) {
        if (columns == null) {
            throw new IllegalStateException("columns 未设置，无法按列名取值: " + col);
        }
        int idx = columns.indexOf(col);
        if (idx < 0) {
            return null;
        }
        return values == null ? null : values.get(idx);
    }
}
