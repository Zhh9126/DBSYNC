package org.dbdatasync.web.controller.logicalio;

import org.dbdatasync.biz.logicalio.CsvLogicalExporter;
import org.dbdatasync.biz.logicalio.CsvLogicalImporter;
import org.dbdatasync.biz.logicalio.InsertSqlExporter;
import org.dbdatasync.biz.logicalio.LogicalRow;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.web.controller.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 逻辑导入导出（Wave17 运行时接线）。
 *
 * <p>「行数据 ↔ 文本格式」纯转换端点：CSV 导出/导入（RFC4180）与三方言 INSERT 导出。
 * 行数据 JSON 形如 {@code {"columns":["id"],"values":["1"]}}；
 * 库端读写由连接器 reader/writer 完成，本端点不连库。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Controller
@RequestMapping("/logical-io")
public class LogicalIoController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final CsvLogicalExporter csvExporter = new CsvLogicalExporter();
    private final CsvLogicalImporter csvImporter = new CsvLogicalImporter();
    private final InsertSqlExporter insertExporter = new InsertSqlExporter();

    /** 行数据 → CSV 文本（首行表头，RFC4180 转义） */
    @PostMapping("/exportCsv")
    @ResponseBody
    public RestResult exportCsv(@RequestBody Map<String, Object> body) {
        try {
            List<LogicalRow> rows = parseRows(body);
            if (rows == null || rows.isEmpty()) {
                return RestResult.restFail("rows 不能为空");
            }
            StringWriter out = new StringWriter();
            long bytes = csvExporter.export(rows, out);
            java.util.Map<String, Object> data = new java.util.HashMap<String, Object>();
            data.put("csv", out.toString());
            data.put("bytes", bytes);
            return RestResult.restSuccess(data);
        } catch (Exception e) {
            logger.error("export csv failed", e);
            return RestResult.restFail("CSV 导出失败：" + e.getMessage());
        }
    }

    /** 行数据 → INSERT SQL（table/dialect/batchSize；dialect=mysql|oracle|pg） */
    @PostMapping("/exportInsert")
    @ResponseBody
    public RestResult exportInsert(@RequestBody Map<String, Object> body) {
        try {
            List<LogicalRow> rows = parseRows(body);
            if (rows == null || rows.isEmpty()) {
                return RestResult.restFail("rows 不能为空");
            }
            String table = str(body.get("table"));
            String dialect = str(body.get("dialect"));
            if (table == null || table.trim().isEmpty()) {
                return RestResult.restFail("table 不能为空");
            }
            if (dialect == null || dialect.trim().isEmpty()) {
                return RestResult.restFail("dialect 不能为空");
            }
            int batchSize = (int) longOf(body.get("batchSize"), 1L);
            StringWriter out = new StringWriter();
            long count = insertExporter.export(rows, table.trim(), dialect.trim(), batchSize, out);
            java.util.Map<String, Object> data = new java.util.HashMap<String, Object>();
            data.put("sql", out.toString());
            data.put("statements", count);
            return RestResult.restSuccess(data);
        } catch (IllegalArgumentException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("export insert failed", e);
            return RestResult.restFail("INSERT 导出失败：" + e.getMessage());
        }
    }

    /** CSV 文本 → 行数据（首行表头映射；列数不一致抛可读错误） */
    @PostMapping("/importCsv")
    @ResponseBody
    public RestResult importCsv(@RequestBody Map<String, Object> body) {
        try {
            String csv = str(body.get("csv"));
            if (csv == null || csv.trim().isEmpty()) {
                return RestResult.restFail("csv 不能为空");
            }
            List<LogicalRow> rows = csvImporter.importCsv(csv);
            List<Map<String, Object>> data = new ArrayList<Map<String, Object>>();
            for (LogicalRow row : rows) {
                java.util.Map<String, Object> item = new java.util.HashMap<String, Object>();
                item.put("columns", row.getColumns());
                item.put("values", row.getValues());
                data.add(item);
            }
            return RestResult.restSuccess(data);
        } catch (IllegalArgumentException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("import csv failed", e);
            return RestResult.restFail("CSV 导入失败：" + e.getMessage());
        }
    }

    // ---------- 解析辅助 ----------

    @SuppressWarnings("unchecked")
    private static List<LogicalRow> parseRows(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("rows");
        if (!(raw instanceof List)) {
            return null;
        }
        List<LogicalRow> rows = new ArrayList<LogicalRow>();
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) item;
            Object cols = map.get("columns");
            Object vals = map.get("values");
            if (!(cols instanceof List) || !(vals instanceof List)) {
                throw new IllegalArgumentException("行数据必须包含 columns 与 values 数组");
            }
            List<String> columns = new ArrayList<String>();
            List<String> values = new ArrayList<String>();
            for (Object c : (List<Object>) cols) {
                columns.add(c == null ? "" : String.valueOf(c));
            }
            for (Object v : (List<Object>) vals) {
                values.add(v == null ? null : String.valueOf(v));
            }
            rows.add(new LogicalRow(columns, values));
        }
        return rows;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longOf(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
