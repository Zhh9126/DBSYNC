package org.dbdatasync.biz.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
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
 * （每行 {@code \r\n} 结尾，不含 BOM）；大结果集请用 {@link #writeTo(Writer, String, List, long)}
 * 直接写 HTTP 响应流，避免整份 CSV String + byte[] 双份物化。</p>
 *
 * <p>Wave30：移除单条明细 200 条的导出封顶（校验明细已不再截断，导出侧却仍丢数据），
 * 并去掉一次性 {@code perRowItems} 全量副本，改为逐行归一化、逐行写出。</p>
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

    private static final String CRLF = "\r\n";
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String TIP_LINE =
        "导出包含明细中全部差异条目（不再按 200 条截断）；差异明细若因外置存储缺失无法还原，导出条数以本 CSV 实际行数为准";

    private ReconciliationCsvBuilder() {
    }

    /** 用当前时间生成 CSV 文本（不含 BOM，BOM 由 web 层写）。 */
    public static String build(String taskId, List<Map<String, Object>> detailRows) {
        return build(taskId, detailRows, System.currentTimeMillis());
    }

    /**
     * 固定时间重载，供单测断言。内部走同一套流式写出逻辑，避免与线上行为分叉。
     */
    public static String build(String taskId, List<Map<String, Object>> detailRows, long timeMillis) {
        StringWriter out = new StringWriter();
        try {
            writeTo(out, taskId, detailRows, timeMillis);
        } catch (IOException e) {
            // StringWriter 不抛 IOException，仅满足 Appendable 契约
            throw new IllegalStateException("构建对账报告失败", e);
        }
        return out.toString();
    }

    /**
     * 流式写出对账报告（推荐用于 Web 下载）：抬头 → 表头 → 逐差异条目展开 → 说明行。
     * 只对每个明细的 content 临时归一化一次，用完即弃，不保存全量 items 副本。
     *
     * @param out        目标 Writer，调用方负责 flush，不负责 close
     * @param taskId     任务 ID
     * @param detailRows 明细行（含 content / diffTotal / fixedTotal 等）
     * @param timeMillis 生成时间
     * @throws IOException 写出失败时抛出
     */
    public static void writeTo(Writer out, String taskId, List<Map<String, Object>> detailRows,
                               long timeMillis) throws IOException {
        final List<Map<String, Object>> rows = detailRows == null
                ? Collections.<Map<String, Object>>emptyList() : detailRows;
        writeTo(out, taskId, (DetailSource) rows::forEach, timeMillis);
    }

    /**
     * Wave30：明细来源抽象。实现必须可重复遍历（构建器需要两遍扫描：统计抬头 + 写数据行）。
     * Web 下载侧传入「分页扫描 DB 的 lambda」，全程只保留当前页 200 行，不再
     * 先把全任务明细塞进 List，也不再有 5000 行静默截断。
     */
    @FunctionalInterface
    public interface DetailSource {
        void forEach(java.util.function.Consumer<Map<String, Object>> sink);
    }

    /**
     * 流式写出（推荐 Web 下载）：两遍扫描 DetailSource，逐行归一化、逐行写出。
     *
     * @throws IOException 写出失败时抛出
     */
    public static void writeTo(Writer out, String taskId, DetailSource source, long timeMillis) throws IOException {
        final DetailSource src = source == null ? sink -> { } : source;
        // 第一遍：只统计汇总数字，不保存任何 items 副本
        final long[] stats = new long[4]; // 0=行数 1=差异总数 2=已订正总数 3=导出条目数
        final boolean[] emptyContentWithDiff = new boolean[1];
        src.forEach(raw -> {
            Map<String, Object> row = raw == null ? Collections.<String, Object>emptyMap() : raw;
            long diff = toLong(row.get("diffTotal"));
            int size = normalizeContent(row.get("content")).size();
            stats[0]++;
            stats[1] += diff;
            stats[2] += toLong(row.get("fixedTotal"));
            stats[3] += size;
            if (diff > 0 && size == 0) {
                emptyContentWithDiff[0] = true;
            }
        });

        boolean incomplete = stats[1] > stats[3] || emptyContentWithDiff[0];
        String completeness = incomplete
            ? ("可能不完整：明细差异数为 " + stats[1] + "，实际导出 " + stats[3]
               + " 条；差异内容可能未落库或外置存储缺失")
            : "完整";

        appendRow(out, "DBDataSync 对账报告");
        appendRow(out, "任务ID", taskId);
        appendRow(out, "生成时间", new SimpleDateFormat(TIME_PATTERN).format(new Date(timeMillis)));
        appendRow(out, "明细行数", Long.valueOf(stats[0]));
        appendRow(out, "差异总数", Long.valueOf(stats[1]));
        appendRow(out, "已订正总数", Long.valueOf(stats[2]));
        appendRow(out, "导出差异条目数", Long.valueOf(stats[3]));
        appendRow(out, "完整性", completeness);
        out.append(CRLF);
        appendRow(out, (Object[]) HEADERS);

        // 第二遍：逐行归一化并直接写出，不累积到内存
        try {
            src.forEach(raw -> {
                try {
                    writeDetailRow(out, raw);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        appendRow(out, "#说明", TIP_LINE);
    }

    /** 展开单条明细的全部差异条目（含字段级 details），不做任何条数封顶。 */
    private static void writeDetailRow(Appendable out, Map<String, Object> raw) throws IOException {
        Map<String, Object> row = raw == null ? Collections.<String, Object>emptyMap() : raw;
        List<Map<String, Object>> items = normalizeContent(row.get("content"));
        for (int idx = 0; idx < items.size(); idx++) {
            Map<String, Object> item = items.get(idx);
            List<Map<String, Object>> details = toDetailList(item.get("details"));
            if (details.isEmpty()) {
                appendDataRow(out, row, idx, item, null);
            } else {
                for (Map<String, Object> d : details) {
                    appendDataRow(out, row, idx, item, d);
                }
            }
        }
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

    /** 逐单元格 escapeCell 后写入 Appendable，末尾 CRLF；调用方传原始值，此处只转义一次。 */
    private static void appendRow(Appendable out, Object... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escapeCell(cells[i]));
        }
        out.append(CRLF);
    }

    /** 一条数据行：前 6 列取明细行，其余取 item / detail，共 17 列。 */
    private static void appendDataRow(Appendable out, Map<String, Object> row, int itemIndex,
                                      Map<String, Object> item, Map<String, Object> detail) throws IOException {
        appendRow(out,
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
