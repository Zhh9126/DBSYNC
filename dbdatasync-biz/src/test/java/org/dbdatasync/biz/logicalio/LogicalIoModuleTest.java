package org.dbdatasync.biz.logicalio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * {@link org.dbdatasync.biz.logicalio} 逻辑导入导出模块单元测试。
 *
 * <p>覆盖：CSV 往返（特殊字符 / 中文 / emoji）、表头映射、列数校验、
 * INSERT 三种方言标识符引用、单引号转义、批量语句数与批内行数、空列表安全。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class LogicalIoModuleTest {

    private final CsvLogicalExporter csvExporter = new CsvLogicalExporter();
    private final CsvLogicalImporter csvImporter = new CsvLogicalImporter();
    private final InsertSqlExporter sqlExporter = new InsertSqlExporter();

    // ---------- CSV 往返 ----------

    /**
     * 特殊字段（含逗号/双引号/换行/CRLF/中文/emoji）经 CSV 导出再导入后，
     * 行数与各字段值应完全一致（round-trip 无损）。
     */
    @Test
    public void testCsvRoundTripWithSpecialCharacters() throws IOException {
        String comma = "a,b";
        String quote = "he said \"hi\"";
        String lf = "line1\nline2";
        String crlf = "row1\r\nrow2";
        String chinese = "中文测试，标点";
        String emoji = "😀🚀ok";

        List<String> cols = Arrays.asList("c1", "c2", "c3", "c4", "c5", "c6");
        List<String> vals = Arrays.asList(comma, quote, lf, crlf, chinese, emoji);
        List<LogicalRow> rows = Arrays.asList(new LogicalRow(cols, vals));

        StringBuilder out = new StringBuilder();
        long bytes = csvExporter.export(rows, out);
        assertTrue(bytes > 0);

        List<LogicalRow> parsed = csvImporter.importCsv(out.toString());
        assertEquals(1, parsed.size());
        assertEquals(6, parsed.get(0).getColumns().size());
        assertEquals(comma, parsed.get(0).getValue("c1"));
        assertEquals(quote, parsed.get(0).getValue("c2"));
        assertEquals(lf, parsed.get(0).getValue("c3"));
        assertEquals(crlf, parsed.get(0).getValue("c4"));
        assertEquals(chinese, parsed.get(0).getValue("c5"));
        assertEquals(emoji, parsed.get(0).getValue("c6"));
    }

    /**
     * 多行数据（含普通行 + 特殊字符行）CSV round-trip 后行数与逐值一致。
     */
    @Test
    public void testCsvRoundTripMultipleRows() throws IOException {
        List<LogicalRow> rows = new ArrayList<LogicalRow>();
        rows.add(new LogicalRow(Arrays.asList("id", "name"),
                Arrays.asList("1", "alice, jr")));
        rows.add(new LogicalRow(Arrays.asList("id", "name"),
                Arrays.asList("2", "中文\"引号\"")));
        rows.add(new LogicalRow(Arrays.asList("id", "name"),
                Arrays.asList("3", "multi\nline")));

        StringBuilder out = new StringBuilder();
        csvExporter.export(rows, out);

        List<LogicalRow> parsed = csvImporter.importCsv(out.toString());
        assertEquals(3, parsed.size());
        assertEquals("alice, jr", parsed.get(0).getValue("name"));
        assertEquals("中文\"引号\"", parsed.get(1).getValue("name"));
        assertEquals("multi\nline", parsed.get(2).getValue("name"));
        assertEquals("3", parsed.get(2).getValue("id"));
    }

    /**
     * 导出的字节数应等于按 UTF-8 编码的实际长度（中文 3 字节、emoji 4 字节）。
     */
    @Test
    public void testCsvExportBytesCount() throws IOException {
        // header: "h" + CRLF = 1 + 2 = 3 字节；data: 中文(6) + CRLF(2) = 8 字节
        List<LogicalRow> rows = Arrays.asList(
                new LogicalRow(Arrays.asList("h"), Arrays.asList("中文")));
        StringBuilder out = new StringBuilder();
        long bytes = csvExporter.export(rows, out);
        assertEquals(11L, bytes);
    }

    // ---------- 表头映射 / 列数校验 ----------

    /**
     * 首行应作为表头，getValue 按列名映射取值；不存在的列返回 null。
     */
    @Test
    public void testCsvHeaderMapping() {
        String csv = "colA,colB\nv1,v2";
        List<LogicalRow> parsed = csvImporter.importCsv(csv);
        assertEquals(1, parsed.size());
        assertEquals(Arrays.asList("colA", "colB"), parsed.get(0).getColumns());
        assertEquals("v1", parsed.get(0).getValue("colA"));
        assertEquals("v2", parsed.get(0).getValue("colB"));
        assertNull(parsed.get(0).getValue("noSuchCol"));
    }

    /**
     * 数据行列数与表头不一致时应抛 IllegalArgumentException，消息含行号。
     */
    @Test
    public void testCsvColumnCountMismatchThrows() {
        String csv = "a,b,c\n1,2\nx,y,z\n3,4,5,6";
        try {
            csvImporter.importCsv(csv);
            fail("应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("消息应包含首个不一致行号 2，实际: " + e.getMessage(),
                    e.getMessage().contains("2"));
        }
    }

    /**
     * 空文本 / null 输入应安全返回空列表；空列表导出不写出内容且字节数为 0。
     */
    @Test
    public void testCsvEmptySafe() throws IOException {
        assertTrue(csvImporter.importCsv(null).isEmpty());
        assertTrue(csvImporter.importCsv("").isEmpty());
        assertTrue(csvImporter.importCsv("\r\n").isEmpty());

        StringBuilder out = new StringBuilder();
        long bytes = csvExporter.export(null, out);
        assertEquals(0L, bytes);
        assertEquals(0, out.length());

        StringBuilder out2 = new StringBuilder();
        long bytes2 = csvExporter.export(new ArrayList<LogicalRow>(), out2);
        assertEquals(0L, bytes2);
        assertEquals(0, out2.length());
    }

    /**
     * 仅表头（无数据行）的 CSV 应解析出 0 条数据且列名可用。
     */
    @Test
    public void testCsvHeaderOnly() {
        List<LogicalRow> parsed = csvImporter.importCsv("a,b");
        assertEquals(0, parsed.size());
    }

    // ---------- INSERT SQL ----------

    /**
     * mysql 方言：标识符用反引号引用。
     */
    @Test
    public void testInsertMysqlDialectQuoting() throws IOException {
        List<LogicalRow> rows = singleRow();
        StringBuilder out = new StringBuilder();
        long count = sqlExporter.export(rows, "t", "mysql", 1, out);
        assertEquals(1L, count);
        assertEquals("INSERT INTO t (`id`,`name`) VALUES ('1','tom');\n", out.toString());
    }

    /**
     * oracle 方言：标识符用双引号大写；pg 方言：双引号小写。
     */
    @Test
    public void testInsertOracleAndPgDialectQuoting() throws IOException {
        StringBuilder oracleOut = new StringBuilder();
        sqlExporter.export(singleRow(), "t", "oracle", 1, oracleOut);
        assertEquals("INSERT INTO t (\"ID\",\"NAME\") VALUES ('1','tom');\n", oracleOut.toString());

        StringBuilder pgOut = new StringBuilder();
        sqlExporter.export(singleRow(), "t", "pg", 1, pgOut);
        assertEquals("INSERT INTO t (\"id\",\"name\") VALUES ('1','tom');\n", pgOut.toString());
    }

    /**
     * 字符串值中的单引号应翻倍转义（' → ''）。
     */
    @Test
    public void testInsertSingleQuoteEscaping() throws IOException {
        List<LogicalRow> rows = Arrays.asList(
                new LogicalRow(Arrays.asList("name"), Arrays.asList("it's ok")));
        StringBuilder out = new StringBuilder();
        sqlExporter.export(rows, "t", "mysql", 1, out);
        assertEquals("INSERT INTO t (`name`) VALUES ('it''s ok');\n", out.toString());
    }

    /**
     * batchSize>1 时 mysql 方言合并为多值 INSERT，语句数与批内行数正确。
     */
    @Test
    public void testInsertMysqlBatchStatements() throws IOException {
        List<LogicalRow> rows = rowsOf(5, "mysql");
        StringBuilder out = new StringBuilder();
        long count = sqlExporter.export(rows, "t", "mysql", 2, out);

        assertEquals(3L, count); // 2 + 2 + 1 → 3 条语句
        assertEquals(3, countOccurrences(out.toString(), "INSERT INTO"));
        // 第一批两个 VALUES 元组，第二批两个，最后一批一个
        assertEquals(1, countOccurrences(out.toString(), "('2','name2')"));
        assertEquals(1, countOccurrences(out.toString(), "('5','name5')"));
    }

    /**
     * batchSize>1 时 pg 方言合并为多值 INSERT。
     */
    @Test
    public void testInsertPgBatchStatements() throws IOException {
        List<LogicalRow> rows = rowsOf(4, "pg");
        StringBuilder out = new StringBuilder();
        long count = sqlExporter.export(rows, "t", "pg", 4, out);

        assertEquals(1L, count);
        assertEquals("INSERT INTO t (\"id\",\"name\") VALUES ('1','name1'),('2','name2'),"
                + "('3','name3'),('4','name4');\n", out.toString());
    }

    /**
     * batchSize>1 时 oracle 方言每批一条 INSERT ALL ... SELECT 1 FROM DUAL。
     */
    @Test
    public void testInsertOracleBatchInsertAll() throws IOException {
        List<LogicalRow> rows = rowsOf(3, "oracle");
        StringBuilder out = new StringBuilder();
        long count = sqlExporter.export(rows, "t", "oracle", 2, out);

        assertEquals(2L, count); // 2 行一批 + 1 行一批
        assertEquals(2, countOccurrences(out.toString(), "INSERT ALL"));
        assertEquals(2, countOccurrences(out.toString(), "SELECT 1 FROM DUAL"));
        assertEquals(3, countOccurrences(out.toString(), "  INTO t ("));
    }

    /**
     * 空列表 / null 列表应安全返回 0 且不写出内容；不支持的方言抛异常。
     */
    @Test
    public void testInsertEmptySafeAndBadDialect() throws IOException {
        StringBuilder out = new StringBuilder();
        assertEquals(0L, sqlExporter.export(null, "t", "mysql", 2, out));
        assertEquals(0L, sqlExporter.export(new ArrayList<LogicalRow>(), "t", "pg", 1, out));
        assertEquals(0, out.length());

        try {
            sqlExporter.export(singleRow(), "t", "sqlserver", 1, new StringBuilder());
            fail("不支持的方言应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("sqlserver"));
        }
    }

    /**
     * batchSize<=1 时每行一条单值 INSERT。
     */
    @Test
    public void testInsertSingleRowStatements() throws IOException {
        List<LogicalRow> rows = rowsOf(3, "mysql");
        StringBuilder out = new StringBuilder();
        long count = sqlExporter.export(rows, "t", "mysql", 0, out);
        assertEquals(3L, count);
        assertEquals(3, countOccurrences(out.toString(), "INSERT INTO"));
    }

    // ---------- LogicalIoResult ----------

    /**
     * LogicalIoResult 各字段 getters/setters 应正常工作。
     */
    @Test
    public void testLogicalIoResultAccessors() {
        LogicalIoResult result = new LogicalIoResult();
        result.setExportedRows(10L);
        result.setExportedBytes(1024L);
        result.setStatementCount(5L);
        result.setImportedRows(8L);
        assertEquals(10L, result.getExportedRows());
        assertEquals(1024L, result.getExportedBytes());
        assertEquals(5L, result.getStatementCount());
        assertEquals(8L, result.getImportedRows());
    }

    // ---------- 辅助方法 ----------

    /**
     * 构造单行测试数据。
     */
    private List<LogicalRow> singleRow() {
        return Arrays.asList(
                new LogicalRow(Arrays.asList("id", "name"), Arrays.asList("1", "tom")));
    }

    /**
     * 构造 n 行测试数据，列固定为 [id, name]，值为 [i, name{i}]。
     */
    private List<LogicalRow> rowsOf(int n, String dialectIgnored) {
        List<LogicalRow> rows = new ArrayList<LogicalRow>();
        for (int i = 1; i <= n; i++) {
            rows.add(new LogicalRow(Arrays.asList("id", "name"),
                    Arrays.asList(String.valueOf(i), "name" + i)));
        }
        return rows;
    }

    /**
     * 统计子串出现次数。
     */
    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
