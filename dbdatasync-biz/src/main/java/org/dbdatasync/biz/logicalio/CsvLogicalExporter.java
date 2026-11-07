package org.dbdatasync.biz.logicalio;

import java.io.IOException;
import java.util.List;

/**
 * CSV 逻辑导出器：将 {@link LogicalRow} 列表序列化为 RFC4180 兼容的 CSV 文本。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>首行写表头（列名，同样按 RFC4180 转义）；</li>
 *   <li>字段含逗号、双引号、换行（\n 或 \r\n）时，用双引号包裹；</li>
 *   <li>被包裹字段内部的双引号翻倍转义（" → ""）；</li>
 *   <li>行分隔符统一使用 \r\n（RFC4180 推荐）；</li>
 *   <li>写出字节数按 UTF-8 编码统计（BOM 不写入）。</li>
 * </ul>
 *
 * <p>本类为纯文本级转换，不涉及 JDBC / 数据库连接。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class CsvLogicalExporter {

    /** RFC4180 推荐的行分隔符。 */
    private static final String CRLF = "\r\n";

    /**
     * 将行数据列表导出为 CSV 文本并写入 {@code out}。
     *
     * @param rows 行数据列表（允许为空或 {@code null}，此时仅写出表头——若能取得列名）
     * @param out  目标输出（StringBuilder / Writer 等 Appendable）
     * @return 写出的字节数（按 UTF-8 编码计）
     * @throws IOException 写出失败时抛出
     */
    public long export(List<LogicalRow> rows, Appendable out) throws IOException {
        long bytes = 0L;
        boolean headerWritten = false;

        if (rows != null) {
            for (LogicalRow row : rows) {
                if (!headerWritten) {
                    bytes += writeLine(out, row.getColumns());
                    headerWritten = true;
                }
                bytes += writeLine(out, row.getValues());
            }
        }
        return bytes;
    }

    /**
     * 写出一行（字段转义 + 行分隔符），并累计 UTF-8 字节数。
     *
     * @param out     目标输出
     * @param fields  字段列表（允许为 {@code null}，跳过不写）
     * @return 该行占用的 UTF-8 字节数
     * @throws IOException 写出失败时抛出
     */
    private long writeLine(Appendable out, List<String> fields) throws IOException {
        if (fields == null) {
            return 0L;
        }
        long bytes = 0L;
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(',');
                bytes++;
            }
            String escaped = escape(fields.get(i));
            out.append(escaped);
            bytes += utf8Length(escaped);
        }
        out.append(CRLF);
        bytes += CRLF.length();
        return bytes;
    }

    /**
     * 按 RFC4180 转义单个字段。
     *
     * @param field 原始字段值（{@code null} 视为空串）
     * @return 转义后的字段值
     */
    private String escape(String field) {
        if (field == null) {
            return "";
        }
        boolean needQuote = field.indexOf(',') >= 0
                || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0;
        if (!needQuote) {
            return field;
        }
        StringBuilder sb = new StringBuilder(field.length() + 8);
        sb.append('"');
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '"') {
                sb.append('"');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 计算字符串按 UTF-8 编码后的字节数（不实际编码，纯统计）。
     *
     * @param s 目标字符串
     * @return UTF-8 字节数
     */
    private long utf8Length(String s) {
        long len = 0L;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                len += 1L;
            } else if (c < 0x800) {
                len += 2L;
            } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length()
                    && s.charAt(i + 1) >= 0xDC00 && s.charAt(i + 1) <= 0xDFFF) {
                // 代理对（如部分 emoji），合占 4 字节
                len += 4L;
                i++;
            } else {
                len += 3L;
            }
        }
        return len;
    }
}
