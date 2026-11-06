package org.dbdatasync.biz.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Wave12 方向一「对账报告导出」的纯静态 CSV 构建器。
 *
 * <p>零 servlet 依赖、可直接单测：{@link #build(String, List, long)} 返回完整 CSV 文本
 * （每行 {@code \r\n} 结尾，不含 BOM），web 层只负责写 UTF-8 BOM 与字节流。
 * 差异明细是 {@code List<Map<String,Object>>}，序列化在校验明细行 {@code DATA} 列，
 * 装配后是铺平的 {@code Map<String,Object>}。</p>
 *
 * <p>关键防御：{@link #normalizeContent(Object)} 对根为 {@code {} 的字符串走
 * {@code JSON.parseObject} 取 {@code data}，绝不对根对象调用 {@code JSON.parseArray}
 * （Wave10「syntax error : {」根因），且任何解析异常都降级为空列表；
 * {@link #escapeCell(Object)} 统一做公式注入防护与标准 CSV 转义，构建器逐单元格只转义一次。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public final class ReconciliationCsvBuilder {

    /** 17 列表头，顺序固定。 */
    public static final String[] HEADERS = {
        "明细ID", "校验类型", "源表", "目标表", "明细差异数", "明细已订正数",
        "差异序号", "差异类型", "主键/标识", "差异说明", "字段名", "源值",
        "目标值", "是否主键", "修复SQL", "订正状态", "备注"
    };

    /** 单条明细最多导出的差异条目数（与 ValidateSyncExecutor.MAX_CONTENT_ITEMS 对齐）。 */
    public static final int MAX_EXPORT_ITEMS_PER_DETAIL = 200;

    private static final String CRLF = "\r\n";
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String TIP_LINE =
        "单条明细最多导出200条差异；校验结果DATA列有65535字节上限，可能被截断；导出条数以本CSV实际行数为准";

    private ReconciliationCsvBuilder() {
    }

    /** 用当前时间生成 CSV 文本（不含 BOM，BOM 由 web 层写）。 */
    public static String build(String taskId, List<Map<String, Object>> detailRows) {
        return build(taskId, detailRows, System.currentTimeMillis());
    }

    /**
     * 固定时间重载，供单测断言。
     *
     * <p>先输出抬头元信息行与 HEADERS，再逐差异条目展开数据行，末尾追加 {@code #说明} 行。
     * 每个差异 item 至少 1 行；{@code details} 为非空 List 时按字段逐行展开，且每行都带
     * 全量公共字段（便于独立解析）。单条明细最多导出 {@link #MAX_EXPORT_ITEMS_PER_DETAIL} 条。</p>
     */
    public static String build(String taskId, List<Map<String, Object>> detailRows, long timeMillis) {
        List<Map<String, Object>> rows;
        if (detailRows == null) {
            rows = Collections.emptyList();
        } else {
            rows = detailRows;
        }

        long sumDiff = 0L;
        long sumFixed = 0L;
        int itemCount = 0;
        boolean emptyContentWithDiff = false;
        List<List<Map<String, Object>>> perRowItems =
            new ArrayList<List<Map<String, Object>>>(rows.size());

        // 第一遍：统计汇总数字、判定完整性、归一化并封顶每条明细的 items
        for (Map<String, Object> raw : rows) {
            Map<String, Object> row = raw == null ? Collections.<String, Object>emptyMap() : raw;
            long diff = toLong(row.get("diffTotal"));
            long fixed = toLong(row.get("fixedTotal"));
            sumDiff += diff;
            sumFixed += fixed;

            List<Map<String, Object>> items = normalizeContent(row.get("content"));
            if (diff > 0 && items.isEmpty()) {
                emptyContentWithDiff = true;
            }
            int limit = Math.min(items.size(), MAX_EXPORT_ITEMS_PER_DETAIL);
            itemCount += limit;
            if (limit == items.size()) {
                perRowItems.add(items);
            } else {
                perRowItems.add(new ArrayList<Map<String, Object>>(items.subList(0, limit)));
            }
        }

        boolean incomplete = sumDiff > itemCount || emptyContentWithDiff;
        String completeness = incomplete
            ? ("可能不完整：明细差异数为 " + sumDiff + "，实际导出 " + itemCount
               + " 条；单条明细最多保留 200 条差异，DATA 列 65535 字节上限可能截断内容")
            : "完整";

        StringBuilder sb = new StringBuilder();
        appendRow(sb, "DBDataSync 对账报告");
        appendRow(sb, "任务ID", taskId);
        appendRow(sb, "生成时间", new SimpleDateFormat(TIME_PATTERN).format(new Date(timeMillis)));
        appendRow(sb, "明细行数", Integer.valueOf(rows.size()));
        appendRow(sb, "差异总数", Long.valueOf(sumDiff));
        appendRow(sb, "已订正总数", Long.valueOf(sumFixed));
        appendRow(sb, "导出差异条目数", Integer.valueOf(itemCount));
        appendRow(sb, "完整性", completeness);
        sb.append(CRLF);
        Object[] headerCells = HEADERS;
        appendRow(sb, headerCells);

        // 第二遍：按差异条目 / 字段展开数据行
        for (int r = 0; r < rows.size(); r++) {
            Map<String, Object> raw = rows.get(r);
            Map<String, Object> row = raw == null ? Collections.<String, Object>emptyMap() : raw;
            List<Map<String, Object>> items = perRowItems.get(r);
            for (int idx = 0; idx < items.size(); idx++) {
                Map<String, Object> item = items.get(idx);
                List<Map<String, Object>> details = toDetailList(item.get("details"));
                if (details.isEmpty()) {
                    appendDataRow(sb, row, idx, item, null);
                } else {
                    for (Map<String, Object> d : details) {
                        appendDataRow(sb, row, idx, item, d);
                    }
                }
            }
        }

        appendRow(sb, "#说明", TIP_LINE);
        return sb.toString();
    }

    /**
     * content 四形态归一化为 item 列表；任何异常返回空 List，绝不抛出。
     *
     * <ol>
     *   <li>{@code List<?>} → 逐项 {@code instanceof Map} 才收；</li>
     *   <li>{@code Map<?,?>} 且 {@code data} 为 List → 收 data；</li>
     *   <li>{@code String}：trim 空 → 空；以 {@code [} 开头 → {@code JSON.parseArray}；
     *       以 <code>{</code> 开头 → {@code JSON.parseObject} 取 {@code data}；其它 / 解析异常 → 空；</li>
     *   <li>其它类型 / null → 空 List。</li>
     * </ol>
     */
    public static List<Map<String, Object>> normalizeContent(Object content) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        try {
            if (content instanceof List<?>) {
                collectMaps((List<?>) content, result);
            } else if (content instanceof Map<?, ?>) {
                Object data = ((Map<?, ?>) content).get("data");
                if (data instanceof List<?>) {
                    collectMaps((List<?>) data, result);
                }
            } else if (content instanceof String) {
                String s = ((String) content).trim();
                if (!s.isEmpty()) {
                    char first = s.charAt(0);
                    if (first == '[') {
                        collectMaps(JSON.parseArray(s), result);
                    } else if (first == '{') {
                        JSONObject obj = JSON.parseObject(s);
                        Object data = obj.get("data");
                        if (data instanceof List<?>) {
                            collectMaps((List<?>) data, result);
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            // 解析异常一律吞掉，降级为空 List（呼应 Wave10）
            return new ArrayList<Map<String, Object>>();
        }
        return result;
    }

    /**
     * CSV 单元格净化 + 标准转义；返回已转义、可直接拼接的单元格文本。
     *
     * <p>null → 空串；公式注入防护（首字符 {@code = + @ \t \r} 或非数字 {@code -}）前置 {@code '}；
     * 含 {@code , " \n \r} 则整体加 {@code "}、内部 {@code "} 替换为 {@code ""}。</p>
     */
    public static String escapeCell(Object value) {
        if (value == null) {
            return "";
        }
        String cell = String.valueOf(value);
        if (cell.isEmpty()) {
            return cell;
        }
        char first = cell.charAt(0);
        boolean prefix = false;
        if (first == '=' || first == '+' || first == '@' || first == '\t' || first == '\r') {
            prefix = true;
        } else if (first == '-' && !NUMBER.matcher(cell).matches()) {
            prefix = true;
        }
        if (prefix) {
            cell = "'" + cell;
        }
        boolean quote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0
            || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
        if (quote) {
            return "\"" + cell.replace("\"", "\"\"") + "\"";
        }
        return cell;
    }

    // ------------------------------------------------------------------
    // 私有辅助
    // ------------------------------------------------------------------

    /** 只收集 List 中的 Map 元素（类型擦除下强转安全，JSONObject 即 Map）。 */
    private static void collectMaps(List<?> source, List<Map<String, Object>> target) {
        if (source == null) {
            return;
        }
        for (Object element : source) {
            if (element instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) element;
                target.add(m);
            }
        }
    }

    /** details 只认 List<Map>；非 List 视为空（至少保留 1 行数据行）。 */
    private static List<Map<String, Object>> toDetailList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        collectMaps(raw instanceof List<?> ? (List<?>) raw : null, out);
        return out;
    }

    /** 汇总数字兼容 Integer / Long / 数字字符串 / null。 */
    private static long toLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignore) {
                return 0L;
            }
        }
        return 0L;
    }

    /** 订正状态由 item.msg 推导。 */
    private static String deriveStatus(Object msgObj) {
        String msg = msgObj == null ? "" : String.valueOf(msgObj);
        if (msg.contains("订正失败")) {
            return "订正失败";
        }
        if (msg.contains("已订正")) {
            return "已订正";
        }
        return "待处理";
    }

    /** 逐单元格 escapeCell 后拼接，末尾 CRLF；调用方传原始值，此处只转义一次。 */
    private static void appendRow(StringBuilder sb, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCell(cells[i]));
        }
        sb.append(CRLF);
    }

    /** 一条数据行：前 6 列取明细行，其余取 item / detail，共 17 列。 */
    private static void appendDataRow(StringBuilder sb, Map<String, Object> row, int itemIndex,
                                      Map<String, Object> item, Map<String, Object> detail) {
        appendRow(sb,
            row.get("id"), row.get("type"), row.get("sourceTableName"), row.get("targetTableName"),
            row.get("diffTotal"), row.get("fixedTotal"),
            Integer.valueOf(itemIndex), item.get("type"), item.get("key"), item.get("msg"),
            detail == null ? null : detail.get("field"),
            detail == null ? null : detail.get("source"),
            detail == null ? null : detail.get("target"),
            detail == null ? null : detail.get("pk"),
            item.get("repairSql"),
            deriveStatus(item.get("msg")),
            null);
    }
}
