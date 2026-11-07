package org.dbdatasync.biz.logicalio;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 逻辑导入器：将 RFC4180 兼容的 CSV 文本解析为 {@link LogicalRow} 列表。
 *
 * <p>支持：</p>
 * <ul>
 *   <li>引号包裹字段、内部双引号转义（"" → "）；</li>
 *   <li>引号内换行（字段值可跨物理行）；</li>
 *   <li>CRLF 与 LF 两种行分隔符；</li>
 *   <li>首行作为表头，映射为每行的列名。</li>
 * </ul>
 *
 * <p>数据行列数与表头不一致时抛 {@link IllegalArgumentException}，消息中包含行号（1 起算，含表头行）。</p>
 *
 * <p>本类为纯文本级转换，不涉及 JDBC / 数据库连接。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class CsvLogicalImporter {

    /**
     * 解析 CSV 文本。
     *
     * @param csvText CSV 文本（允许 {@code null} 或空白，返回空列表）
     * @return 解析出的行数据列表（首行表头已映射为列名）
     * @throws IllegalArgumentException 数据行列数与表头不一致时抛出（消息含行号）
     */
    public List<LogicalRow> importCsv(String csvText) {
        List<LogicalRow> result = new ArrayList<LogicalRow>();
        if (csvText == null || csvText.isEmpty()) {
            return result;
        }

        List<List<String>> records = parseRecords(csvText);
        if (records.isEmpty()) {
            return result;
        }

        List<String> header = records.get(0);
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.size() != header.size()) {
                throw new IllegalArgumentException(
                        "第 " + (i + 1) + " 行列数(" + record.size()
                                + ")与表头列数(" + header.size() + ")不一致");
            }
            result.add(new LogicalRow(new ArrayList<String>(header), record));
        }
        return result;
    }

    /**
     * 将 CSV 文本按状态机解析为记录列表（首条记录即表头）。
     *
     * <p>状态机：IN_QUOTES / ESCAPED_QUOTE / IN_FIELD / 普通逐字符扫描，
     * 可正确处理引号内逗号、引号内换行与连续转义引号。</p>
     *
     * @param text CSV 文本
     * @return 记录列表；每条记录为字段列表
     */
    private List<List<String>> parseRecords(String text) {
        List<List<String>> records = new ArrayList<List<String>>();
        List<String> current = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;
        int len = text.length();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // 判断是否为转义双引号 ""
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (c == '"' && field.length() == 0 && !fieldStarted) {
                inQuotes = true;
                fieldStarted = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\r') {
                // 兼容 CR / CRLF
                if (i + 1 < len && text.charAt(i + 1) == '\n') {
                    i++;
                }
                endRecord(records, current, field);
                fieldStarted = false;
            } else if (c == '\n') {
                endRecord(records, current, field);
                fieldStarted = false;
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }

        // 文件末尾无换行时，最后一条记录补收
        if (field.length() > 0 || fieldStarted || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }

    /**
     * 结束一条记录：收尾字段并加入记录列表。
     *
     * @param records 记录列表
     * @param current 当前记录的字段列表
     * @param field   当前字段缓冲
     */
    private void endRecord(List<List<String>> records, List<String> current, StringBuilder field) {
        current.add(field.toString());
        field.setLength(0);
        records.add(new ArrayList<String>(current));
        current.clear();
    }
}
