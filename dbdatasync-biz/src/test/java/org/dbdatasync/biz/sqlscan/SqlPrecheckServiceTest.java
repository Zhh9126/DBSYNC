package org.dbdatasync.biz.sqlscan;

import org.dbdatasync.biz.vo.SqlPrecheckReportVO;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave10 T05：可选 SQL 兼容度预检单测。
 *
 * <p>纯桩/临时目录（{@link Files#createTempDirectory}），不连库、不启服务、不写真实数据。
 * 覆盖：多语句文本、临时目录两类输入的分级计数与问题清单；未输入零副作用（不调用扫描器）；
 * 目录不存在/系统目录可读提示；混合输入；未知方言 info 降级；
 * 既有 {@code PreCheckService.assertPassable} / {@code POST /precheck} 零改动回归。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class SqlPrecheckServiceTest {

    /** 计数扫描器：用于断言「未输入时不调用扫描器」 */
    private static final class CountingScanner extends SqlCompatibilityScanner {
        private int calls = 0;

        @Override
        public SqlScanResult scan(String sql, String sourceDialect, String targetDialect) {
            calls++;
            return super.scan(sql, sourceDialect, targetDialect);
        }
    }

    @Test
    public void textInput_producesGradedCountsAndIssues() {
        CountingScanner scanner = new CountingScanner();
        SqlPrecheckService service = serviceWith(scanner);
        String sql = "SELECT NVL(a, 0) FROM t WHERE ROWNUM <= 10;"
                + "SELECT id FROM emp START WITH id = 1 CONNECT BY PRIOR id = mgr_id;";
        SqlPrecheckReportVO report = service.precheck(sql, "", "Oracle", "MySQL");

        assertEquals(1, scanner.calls);
        assertEquals("text", report.getInputMode());
        assertFalse(report.isBlocking());
        assertEquals(SqlPrecheckService.HINT, report.getHint());
        assertNotNull(report.getResult());
        assertTrue(report.getResult().isScanned());
        assertTrue(report.getResult().getStatementCount() >= 2);
        assertTrue(report.getResult().getErrorCount() > 0);
        assertTrue(report.getResult().getWarnCount() > 0);
        assertFalse(report.getResult().getIssues().isEmpty());
        assertTrue(hasCode(report.getResult(), "SQL-F01"));
        assertTrue(report.getResult().getErrorCount() + report.getResult().getWarnCount()
                + report.getResult().getInfoCount() == report.getResult().getIssues().size());
    }

    @Test
    public void directoryInput_producesGradedCountsAndIssues() throws Exception {
        Path dir = Files.createTempDirectory("wave10-sqlprecheck-");
        try {
            Files.write(dir.resolve("a_nvl.sql"),
                    "SELECT NVL(a, 0) FROM t;".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("b_rownum.sql"),
                    "SELECT * FROM t WHERE ROWNUM <= 5;".getBytes(StandardCharsets.UTF_8));
            // 非 .sql 文件必须被忽略
            Files.write(dir.resolve("ignore.txt"),
                    "SELECT SYSDATE FROM t;".getBytes(StandardCharsets.UTF_8));

            CountingScanner scanner = new CountingScanner();
            SqlPrecheckService service = serviceWith(scanner);
            SqlPrecheckReportVO report = service.precheck("", dir.toString(), "Oracle", "MySQL");

            assertEquals(1, scanner.calls);
            assertEquals("directory", report.getInputMode());
            assertNotNull(report.getResult());
            assertTrue(report.getResult().getStatementCount() >= 2);
            assertFalse(report.getResult().getIssues().isEmpty());
            assertTrue(report.getInputSummary().contains("2 个 .sql 文件"));
            assertFalse(report.getInputSummary().contains("ignore.txt"));
            assertTrue(hasCode(report.getResult(), "SQL-F01"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void mixedInput_mergesTextAndDirectory() throws Exception {
        Path dir = Files.createTempDirectory("wave10-sqlprecheck-mixed-");
        try {
            Files.write(dir.resolve("only.sql"),
                    "SELECT DECODE(x, 1, 'Y', 'N') FROM t;".getBytes(StandardCharsets.UTF_8));
            CountingScanner scanner = new CountingScanner();
            SqlPrecheckService service = serviceWith(scanner);
            SqlPrecheckReportVO report = service.precheck(
                    "SELECT NVL(a, 0) FROM t;", dir.toString(), "Oracle", "MySQL");

            assertEquals("mixed", report.getInputMode());
            assertEquals(1, scanner.calls);
            assertTrue(report.getResult().getStatementCount() >= 2);
            assertTrue(hasCode(report.getResult(), "SQL-F01"));
            assertTrue(hasCode(report.getResult(), "SQL-F02"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void noInput_hasZeroSideEffect() {
        CountingScanner scanner = new CountingScanner();
        SqlPrecheckService service = serviceWith(scanner);
        SqlPrecheckReportVO report = service.precheck("", "", "Oracle", "MySQL");

        assertEquals(0, scanner.calls);
        assertEquals("none", report.getInputMode());
        assertNull(report.getResult());
        assertTrue(report.getInputSummary().contains("未输入"));
        assertFalse(report.isBlocking());
        assertEquals(SqlPrecheckService.HINT, report.getHint());

        SqlPrecheckReportVO nullReport = service.precheck(null, null, null, null);
        assertEquals(0, scanner.calls);
        assertNull(nullReport.getResult());
        assertTrue(nullReport.getInputSummary().contains("未输入"));
    }

    @Test
    public void missingDirectory_givesReadableHint() throws Exception {
        Path dir = Files.createTempDirectory("wave10-sqlprecheck-missing-");
        try {
            Path missing = dir.resolve("not-exists");
            CountingScanner scanner = new CountingScanner();
            SqlPrecheckService service = serviceWith(scanner);
            SqlPrecheckReportVO report = service.precheck("", missing.toString(), "Oracle", "MySQL");
            assertTrue(report.getInputSummary().contains("目录不存在或不可读"));
            assertNull(report.getResult());
            assertEquals(0, scanner.calls);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void forbiddenSystemDirectories_giveReadableHint() {
        CountingScanner scanner = new CountingScanner();
        SqlPrecheckService service = serviceWith(scanner);

        String home = System.getProperty("user.home");
        if (home != null && !home.trim().isEmpty()) {
            SqlPrecheckReportVO homeReport = service.precheck("", home, "Oracle", "MySQL");
            assertTrue(homeReport.getInputSummary().contains("不允许扫描"));
            assertNull(homeReport.getResult());
        }

        Path root = Paths.get(".").toAbsolutePath().getRoot();
        if (root != null) {
            SqlPrecheckReportVO rootReport = service.precheck("", root.toString(), "Oracle", "MySQL");
            assertTrue(rootReport.getInputSummary().contains("不允许扫描"));
            assertNull(rootReport.getResult());
        }

        SqlPrecheckReportVO etcReport = service.precheck("", "/etc", "Oracle", "MySQL");
        assertTrue(etcReport.getInputSummary().contains("不允许扫描"));
        assertEquals(0, scanner.calls);
    }

    @Test
    public void unknownDialect_degradesToInfo() {
        CountingScanner scanner = new CountingScanner();
        SqlPrecheckService service = serviceWith(scanner);
        SqlPrecheckReportVO report = service.precheck("SELECT 1", "", "Hive", "MySQL");
        assertNotNull(report.getResult());
        assertFalse(report.getResult().isScanned());
        assertTrue(hasCode(report.getResult(), "sql-unsupported-dialect"));
        assertEquals(1, report.getResult().getInfoCount());
    }

    @Test
    public void thresholdsAreCentralized() {
        assertEquals(3, SqlPrecheckService.MAX_DEPTH);
        assertEquals(200, SqlPrecheckService.MAX_FILES);
        assertEquals(1024L * 1024L, SqlPrecheckService.MAX_FILE_BYTES);
        assertEquals(10L * 1024L * 1024L, SqlPrecheckService.MAX_TOTAL_BYTES);
    }

    @Test
    public void existingPrecheckServiceAndAssertPassableUntouched() throws Exception {
        // 既有 PreCheckService / assertPassable / POST /precheck 本轮零改动，仅验证 biz 侧契约仍在。
        // web 侧 Controller 由全量离线编译（dbdatasync-web -am）保障，不在 biz 单测 classpath 内引用。
        Class<?> clazz = Class.forName("org.dbdatasync.biz.precheck.PreCheckService");
        assertNotNull(clazz.getDeclaredMethod("preCheckDatabaseSync", String.class));
        assertNotNull(clazz.getDeclaredMethod("assertPassable", String.class, String.class));
    }

    // ============ 工具 ============

    private SqlPrecheckService serviceWith(SqlCompatibilityScanner scanner) {
        SqlPrecheckService service = new SqlPrecheckService();
        service.setScanner(scanner);
        return service;
    }

    private boolean hasCode(SqlScanResult result, String code) {
        for (SqlCompatibilityIssue issue : result.getIssues()) {
            if (code.equals(issue.getCode())) {
                return true;
            }
        }
        return false;
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 清理失败不影响断言
                }
            });
        }
    }
}
