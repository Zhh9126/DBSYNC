package org.dbdatasync.biz.qa;

import org.dbdatasync.biz.util.ReconciliationCsvBuilder;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave12 方向一「对账报告 CSV 构建器」回归用例。
 *
 * <p>JUnit 4；照抄 Wave11CostMetricsTest / Wave10QaEdgeCaseTest 的 import 与写法。
 * 纯静态调用，不连库、不启服务、不写 Meta、不改任何生产源码。覆盖：空/null rows、
 * HEADERS 17 列、content 四形态 + 坏 JSON、details 字段级展开、CSV 转义、公式注入、
 * 完整性提示、汇总求和、tip 类型、null 字段、固定时间稳定、全量导出不截断。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class Wave12CsvBuilderTest {

    private static final long FIXED = 1700000000123L;

    // ==================== ① HEADERS ====================

    @Test
    public void headersLengthIsSeventeen() {
        assertEquals(17, ReconciliationCsvBuilder.HEADERS.length);
        assertEquals("明细ID", ReconciliationCsvBuilder.HEADERS[0]);
        assertEquals("备注", ReconciliationCsvBuilder.HEADERS[16]);
    }

    // ==================== ② 空 / null rows ====================

    @Test
    public void emptyRows_onlyHeaderSummaryAndTip() {
        String csv = ReconciliationCsvBuilder.build("T-EMPTY",
            Collections.<Map<String, Object>>emptyList(), FIXED);
        String headerLine = String.join(",", ReconciliationCsvBuilder.HEADERS);
        assertTrue(csv.startsWith("DBDataSync 对账报告\r\n"));
        assertTrue(csv.contains("任务ID,T-EMPTY\r\n"));
        assertTrue(csv.contains("明细行数,0\r\n"));
        assertTrue(csv.contains("差异总数,0\r\n"));
        assertTrue(csv.contains("已订正总数,0\r\n"));
        assertTrue(csv.contains("导出差异条目数,0\r\n"));
        assertTrue(csv.contains("完整性,完整\r\n\r\n" + headerLine + "\r\n#说明,"));
        assertTrue(csv.endsWith("\r\n"));
    }

    @Test
    public void nullRows_safeAndSameAsEmpty() {
        String csv = ReconciliationCsvBuilder.build("T-NULL", null, FIXED);
        assertNotNull(csv);
        assertTrue(csv.contains("明细行数,0\r\n"));
        assertTrue(csv.contains("完整性,完整\r\n"));
        assertEquals(ReconciliationCsvBuilder.build("T-NULL",
            Collections.<Map<String, Object>>emptyList(), FIXED), csv);
    }

    // ==================== ③ 时间重载 ====================

    @Test
    public void currentTimeOverload_noThrow() {
        String csv = ReconciliationCsvBuilder.build("T",
            Collections.<Map<String, Object>>emptyList());
        assertNotNull(csv);
        assertTrue(csv.contains("生成时间,"));
    }

    @Test
    public void fixedTimeMillis_outputStable() {
        List<Map<String, Object>> rows = Collections.singletonList(
            map("id", "D-1", "diffTotal", 1, "content", list(map("type", "rowData"))));
        String a = ReconciliationCsvBuilder.build("T", rows, FIXED);
        String b = ReconciliationCsvBuilder.build("T", rows, FIXED);
        assertEquals(a, b);
        String expected = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(FIXED));
        assertTrue(a.contains("生成时间," + expected + "\r\n"));
    }

    // ==================== ④ normalizeContent 四形态 ====================

    @Test
    public void normalizeContent_listForm() {
        List<Map<String, Object>> in = list(map("type", "rowData"), map("type", "tip"));
        List<Map<String, Object>> out = ReconciliationCsvBuilder.normalizeContent(in);
        assertEquals(2, out.size());
        assertEquals("rowData", out.get(0).get("type"));
        assertEquals("tip", out.get(1).get("type"));
    }

    @Test
    public void normalizeContent_mapWithDataList() {
        Map<String, Object> wrapper = map("data", list(map("k", "v")));
        assertEquals(1, ReconciliationCsvBuilder.normalizeContent(wrapper).size());
        Map<String, Object> noList = map("data", "not-a-list");
        assertTrue(ReconciliationCsvBuilder.normalizeContent(noList).isEmpty());
        Map<String, Object> noData = map("other", list(map("k", "v")));
        assertTrue(ReconciliationCsvBuilder.normalizeContent(noData).isEmpty());
    }

    @Test
    public void normalizeContent_jsonArrayString() {
        String s = "[{\"type\":\"rowData\"},{\"type\":\"tip\"}]";
        List<Map<String, Object>> out = ReconciliationCsvBuilder.normalizeContent(s);
        assertEquals(2, out.size());
        assertEquals("rowData", out.get(0).get("type"));
        assertEquals("tip", out.get(1).get("type"));
    }

    @Test
    public void normalizeContent_jsonObjectString_noParseArrayBug() {
        // 根为 { 必须走 parseObject 取 data；若误调 parseArray 会返回空，此断言即失败
        String s = "{\"data\":[{\"type\":\"rowData\",\"msg\":\"x\"}]}";
        List<Map<String, Object>> out = ReconciliationCsvBuilder.normalizeContent(s);
        assertEquals(1, out.size());
        assertEquals("rowData", out.get(0).get("type"));
        assertEquals("x", out.get(0).get("msg"));
    }

    @Test
    public void normalizeContent_badJsonAndOtherForms_returnEmpty_noThrow() {
        assertTrue(ReconciliationCsvBuilder.normalizeContent(null).isEmpty());
        assertTrue(ReconciliationCsvBuilder.normalizeContent("").isEmpty());
        assertTrue(ReconciliationCsvBuilder.normalizeContent("   ").isEmpty());
        assertTrue(ReconciliationCsvBuilder.normalizeContent("[not-json").isEmpty());
        assertTrue(ReconciliationCsvBuilder.normalizeContent("{").isEmpty());
        assertTrue(ReconciliationCsvBuilder.normalizeContent("{\"data\":\"notlist\"}").isEmpty());
        assertTrue(ReconciliationCsvBuilder.normalizeContent("plain text").isEmpty());
        assertTrue(ReconciliationCsvBuilder.normalizeContent(Integer.valueOf(5)).isEmpty());
        assertTrue(ReconciliationCsvBuilder.normalizeContent(new Object()).isEmpty());
        assertTrue(ReconciliationCsvBuilder.normalizeContent(Arrays.asList(1, 2, 3)).isEmpty());
    }

    // ==================== ⑤ details 字段级展开 ====================

    @Test
    public void detailsExpand_fieldLevelRowsWithAllCommonColumns() {
        Map<String, Object> d1 = map("field", "F_NAME", "source", "a", "target", "b",
            "pk", Boolean.TRUE);
        Map<String, Object> d2 = map("field", "F_AGE", "source", "1", "target", "2",
            "pk", Boolean.FALSE);
        Map<String, Object> item = map("type", "rowData", "msg", "字段不一致", "key", "PK=7",
            "repairSql", "UPDATE t SET x=1", "details", list(d1, d2));
        Map<String, Object> row = map("id", "D-1", "type", "rowData", "sourceTableName", "SRC",
            "targetTableName", "TGT", "diffTotal", 1, "fixedTotal", 0, "content", list(item));
        String csv = ReconciliationCsvBuilder.build("T", Collections.singletonList(row), FIXED);
        int dataRows = 0;
        boolean hasName = false;
        boolean hasAge = false;
        for (String ln : lines(csv)) {
            if (ln.startsWith("D-1,")) {
                dataRows++;
                assertEquals(17, ln.split(",", -1).length);
                assertTrue(ln.contains("SRC"));
                assertTrue(ln.contains("TGT"));
                assertTrue(ln.contains("PK=7"));
                assertTrue(ln.contains("UPDATE t SET x=1"));
                assertTrue(ln.contains("待处理"));
                if (ln.contains("F_NAME")) {
                    hasName = true;
                }
                if (ln.contains("F_AGE")) {
                    hasAge = true;
                }
            }
        }
        assertEquals(2, dataRows);
        assertTrue(hasName);
        assertTrue(hasAge);
    }

    // ==================== ⑥ escapeCell 转义 / 公式注入 ====================

    @Test
    public void escapeCell_commaQuoteCrlf() {
        assertEquals("\"a,b\"", ReconciliationCsvBuilder.escapeCell("a,b"));
        assertEquals("\"a\"\"b\"", ReconciliationCsvBuilder.escapeCell("a\"b"));
        assertEquals("\"a\r\nb\"", ReconciliationCsvBuilder.escapeCell("a\r\nb"));
        assertEquals("\"a,\"\"b\"\"\r\nc\"", ReconciliationCsvBuilder.escapeCell("a,\"b\"\r\nc"));
    }

    @Test
    public void escapeCell_formulaInjection_prefixed() {
        assertEquals("'=cmd", ReconciliationCsvBuilder.escapeCell("=cmd"));
        assertEquals("'+1", ReconciliationCsvBuilder.escapeCell("+1"));
        assertEquals("'@SUM", ReconciliationCsvBuilder.escapeCell("@SUM"));
        assertEquals("'-abc", ReconciliationCsvBuilder.escapeCell("-abc"));
        assertEquals("'\tX", ReconciliationCsvBuilder.escapeCell("\tX"));
        // \r 开头前置 ' 后仍需整体加引号（CR 触发标准 CSV 转义）
        assertTrue(ReconciliationCsvBuilder.escapeCell("\rX").startsWith("\"'"));
    }

    @Test
    public void escapeCell_negativeNumbersAndDecimals_unchanged() {
        assertEquals("-123", ReconciliationCsvBuilder.escapeCell("-123"));
        assertEquals("0.5", ReconciliationCsvBuilder.escapeCell("0.5"));
        assertEquals("-123.45", ReconciliationCsvBuilder.escapeCell("-123.45"));
        assertEquals("0", ReconciliationCsvBuilder.escapeCell(Integer.valueOf(0)));
    }

    @Test
    public void escapeCell_nullAndEmpty() {
        assertEquals("", ReconciliationCsvBuilder.escapeCell(null));
        assertEquals("", ReconciliationCsvBuilder.escapeCell(""));
    }

    // ==================== ⑦ 完整性 ====================

    @Test
    public void incomplete_whenDiffTotalGreaterThanExported() {
        Map<String, Object> row = map("id", "D-1", "diffTotal", 5,
            "content", list(map("type", "rowData"), map("type", "rowData")));
        String csv = ReconciliationCsvBuilder.build("T", Collections.singletonList(row), FIXED);
        assertTrue(csv.contains("完整性,可能不完整"));
        assertTrue(csv.contains("明细差异数为 5"));
        assertTrue(csv.contains("实际导出 2 条"));
        assertTrue(csv.contains("差异内容可能未落库"));
    }

    @Test
    public void incomplete_whenContentEmptyButDiffPositive() {
        Map<String, Object> row = map("diffTotal", 3, "content", "[bad-json");
        String csv = ReconciliationCsvBuilder.build("T", Collections.singletonList(row), FIXED);
        assertTrue(csv.contains("完整性,可能不完整"));
        assertTrue(csv.contains("明细差异数为 3"));
        assertTrue(csv.contains("实际导出 0 条"));
    }

    @Test
    public void complete_whenFullyExported() {
        Map<String, Object> row = map("diffTotal", 2,
            "content", list(map("type", "rowData"), map("type", "tip")));
        String csv = ReconciliationCsvBuilder.build("T", Collections.singletonList(row), FIXED);
        assertTrue(csv.contains("完整性,完整\r\n"));
        assertTrue(csv.contains("导出差异条目数,2\r\n"));
    }

    // ==================== ⑧ 汇总 / tip / 状态 ====================

    @Test
    public void summaryNumbers_sumAcrossRows() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(map("diffTotal", Integer.valueOf(2), "fixedTotal", Integer.valueOf(1)));
        rows.add(map("diffTotal", Long.valueOf(3), "fixedTotal", Long.valueOf(0)));
        rows.add(map("diffTotal", "4", "fixedTotal", "2"));
        String csv = ReconciliationCsvBuilder.build("T", rows, FIXED);
        assertTrue(csv.contains("明细行数,3\r\n"));
        assertTrue(csv.contains("差异总数,9\r\n"));
        assertTrue(csv.contains("已订正总数,3\r\n"));
    }

    @Test
    public void tipType_exportedAtLeastOneRow() {
        Map<String, Object> row = map("id", "D-TIP", "diffTotal", 1,
            "content", list(map("type", "tip", "msg", "目标多余", "key", "N/A")));
        String csv = ReconciliationCsvBuilder.build("T", Collections.singletonList(row), FIXED);
        int tipRows = 0;
        for (String ln : lines(csv)) {
            if (ln.startsWith("D-TIP,")) {
                tipRows++;
                assertTrue(ln.contains(",tip,"));
                assertTrue(ln.contains("目标多余"));
                assertTrue(ln.contains("待处理"));
                assertEquals(17, ln.split(",", -1).length);
            }
        }
        assertEquals(1, tipRows);
    }

    @Test
    public void deriveStatus_threeVariants() {
        Map<String, Object> row = map("id", "D-S", "diffTotal", 3, "content", list(
            map("type", "rowData", "msg", "不一致 (已订正)"),
            map("type", "rowData", "msg", "修不了 (订正失败: err)"),
            map("type", "rowData", "msg", "源缺记录")));
        String csv = ReconciliationCsvBuilder.build("T", Collections.singletonList(row), FIXED);
        assertTrue(csv.contains("已订正"));
        assertTrue(csv.contains("订正失败"));
        assertTrue(csv.contains("待处理"));
    }

    // ==================== ⑨ 健壮性 / 封顶 ====================

    @Test
    public void nullFields_noNpe() {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("content", list(new HashMap<String, Object>()));
        String csv = ReconciliationCsvBuilder.build(null, Collections.singletonList(row), FIXED);
        assertNotNull(csv);
        assertTrue(csv.contains("任务ID,\r\n"));
        assertTrue(csv.contains(String.join(",", ReconciliationCsvBuilder.HEADERS)));
        boolean hasEmptyRow = false;
        for (String ln : lines(csv)) {
            if (ln.startsWith(",") && ln.split(",", -1).length == 17) {
                hasEmptyRow = true;
            }
        }
        assertTrue(hasEmptyRow);
    }

    @Test
    public void allItemsExported_withoutCap() {
        List<Map<String, Object>> many = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 250; i++) {
            many.add(map("type", "rowData", "key", "K" + i));
        }
        Map<String, Object> row = map("id", "D-MANY", "diffTotal", 250, "content", many);
        String csv = ReconciliationCsvBuilder.build("T", Collections.singletonList(row), FIXED);
        // Wave30：校验明细已完整保留，导出侧不得再按 200 条截断
        assertTrue(csv.contains("导出差异条目数,250\r\n"));
        int dataRows = 0;
        for (String ln : lines(csv)) {
            if (ln.startsWith("D-MANY,")) {
                dataRows++;
            }
        }
        assertEquals(250, dataRows);
        assertTrue(csv.contains("完整性,完整\r\n"));
    }

    // ==================== 工具 ====================

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @SafeVarargs
    private static List<Map<String, Object>> list(Map<String, Object>... ms) {
        return new ArrayList<Map<String, Object>>(Arrays.asList(ms));
    }

    private static String[] lines(String csv) {
        return csv.split("\r\n", -1);
    }
}
