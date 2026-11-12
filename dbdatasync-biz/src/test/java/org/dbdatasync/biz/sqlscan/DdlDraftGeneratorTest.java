package org.dbdatasync.biz.sqlscan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave10 T03：DDL 建表语句转换草稿单测。
 *
 * <p>覆盖：≥5 条 Q3 类型映射、error 级（ROWID）不生成草稿、无改动返回原 SQL、
 * 草稿注释头与标签、无 JDBC、以及 Wave5/Wave8 既有扫描器 17 项回归的核心路径
 * （全量 {@code mvn -o -pl dbdatasync-biz -am test} 会跑完 17 项）。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class DdlDraftGeneratorTest {

    private final DdlDraftGenerator generator = new DdlDraftGenerator();

    @Test
    public void varchar2AndNvarchar2WithLength() {
        String ddl = "CREATE TABLE t (a VARCHAR2(64), b NVARCHAR2(32))";
        DdlDraft draft = generator.generate(ddl, "Oracle", "MySQL");
        assertTrue(draft.isChanged());
        assertEquals(SqlCompatibilityIssue.LEVEL_WARN, draft.getLevel());
        assertTrue(draft.getDraftDdl().contains("VARCHAR(64)"));
        assertTrue(draft.getDraftDdl().contains("VARCHAR(32)"));
        assertFalse(draft.getDraftDdl().contains("VARCHAR2"));
        assertFalse(draft.getDraftDdl().contains("NVARCHAR2"));
        assertTrue(draft.getMappedTypes().contains("VARCHAR2"));
        assertTrue(draft.getMappedTypes().contains("NVARCHAR2"));
    }

    @Test
    public void bareVarchar2UsesDefaultLength() {
        DdlDraft draft = generator.generate("CREATE TABLE t (a VARCHAR2, b NVARCHAR2)", "Oracle", "MySQL");
        assertTrue(draft.isChanged());
        assertTrue(draft.getDraftDdl().contains("VARCHAR(255)"));
        assertEquals(2, countOccurrences(draft.getDraftDdl(), "VARCHAR(255)"));
        assertTrue(draft.getDraftDdl().startsWith(DdlDraftGenerator.DRAFT_HEADER + "\n"));
    }

    @Test
    public void numberPrecisionMapping() {
        String ddl = "CREATE TABLE t (a NUMBER(10,2), b NUMBER(8), c NUMBER)";
        DdlDraft draft = generator.generate(ddl, "Oracle", "MySQL");
        assertTrue(draft.isChanged());
        assertTrue(draft.getDraftDdl().contains("DECIMAL(10,2)"));
        assertTrue(draft.getDraftDdl().contains("DECIMAL(8,0)"));
        assertTrue(draft.getDraftDdl().contains("DECIMAL(18,4)"));
        assertFalse(draft.getDraftDdl().contains("NUMBER"));
    }

    @Test
    public void clobAndBooleanMapping() {
        String ddl = "CREATE TABLE t (memo CLOB, flag BOOLEAN)";
        DdlDraft draft = generator.generate(ddl, "Oracle", "MySQL");
        assertTrue(draft.isChanged());
        // CLOB→TEXT 沿用 Wave8，不改 LONGTEXT；BOOLEAN→TINYINT(1) 对齐 SQL-T08
        assertTrue(draft.getDraftDdl().contains("TEXT"));
        assertFalse(draft.getDraftDdl().contains("LONGTEXT"));
        assertTrue(draft.getDraftDdl().contains("TINYINT(1)"));
        assertTrue(draft.getMappedTypes().contains("CLOB"));
        assertTrue(draft.getMappedTypes().contains("BOOLEAN"));
    }

    @Test
    public void mixedDdlCoversAtLeastFiveTypeMappings() {
        String ddl = "CREATE TABLE t (id NUMBER(10), name VARCHAR2(64), nick NVARCHAR2(32), "
                + "amount NUMBER(12,3), rate NUMBER, memo CLOB, flag BOOLEAN, addr VARCHAR2)";
        DdlDraft draft = generator.generate(ddl, "Oracle", "MySQL");
        assertTrue(draft.isChanged());
        String mapped = draft.getDraftDdl();
        assertTrue(mapped.contains("DECIMAL(10,0)"));
        assertTrue(mapped.contains("DECIMAL(12,3)"));
        assertTrue(mapped.contains("DECIMAL(18,4)"));
        assertTrue(mapped.contains("VARCHAR(64)"));
        assertTrue(mapped.contains("VARCHAR(32)"));
        assertTrue(mapped.contains("VARCHAR(255)"));
        assertTrue(mapped.contains("TEXT"));
        assertTrue(mapped.contains("TINYINT(1)"));
        assertEquals(5, draft.getMappedTypes().size());
        assertTrue(mapped.startsWith(DdlDraftGenerator.DRAFT_HEADER));
        assertTrue(draft.getNote().contains("需人工确认"));
    }

    @Test
    public void errorLevelRowidDoesNotGenerateDraft() {
        DdlDraft draft = generator.generate(
                "CREATE TABLE t (rid ROWID, name VARCHAR2(10))", "Oracle", "MySQL");
        assertFalse(draft.isChanged());
        assertEquals(SqlCompatibilityIssue.LEVEL_ERROR, draft.getLevel());
        assertEquals("", draft.getDraftDdl());
        assertTrue(draft.getNote().contains("需人工重写"));
        assertFalse(draft.getDraftDdl().contains(DdlDraftGenerator.DRAFT_HEADER));
    }

    @Test
    public void errorLevelUrowidDoesNotGenerateDraft() {
        DdlDraft draft = generator.generate(
                "CREATE TABLE t (rid UROWID, name VARCHAR2(10))", "Oracle", "MySQL");
        assertFalse(draft.isChanged());
        assertEquals(SqlCompatibilityIssue.LEVEL_ERROR, draft.getLevel());
        assertEquals("", draft.getDraftDdl());
    }

    @Test
    public void noMappingHitReturnsOriginalSql() {
        String ddl = "CREATE TABLE t (id INT, name VARCHAR(64), created DATE)";
        DdlDraft draft = generator.generate(ddl, "Oracle", "MySQL");
        assertFalse(draft.isChanged());
        assertEquals(SqlCompatibilityIssue.LEVEL_INFO, draft.getLevel());
        assertEquals(ddl, draft.getDraftDdl());
        assertEquals(ddl, draft.getOriginalDdl());
        assertFalse(draft.getDraftDdl().contains(DdlDraftGenerator.DRAFT_HEADER));
        assertTrue(draft.getNote().contains("无映射命中"));
        assertTrue(draft.getMappedTypes().isEmpty());
    }

    @Test
    public void blankInputIsSafe() {
        DdlDraft draft = generator.generate("   ", "Oracle", "MySQL");
        assertFalse(draft.isChanged());
        assertEquals("", draft.getOriginalDdl().trim());
        assertEquals(draft.getOriginalDdl(), draft.getDraftDdl());
        DdlDraft nullDraft = generator.generate(null, "Oracle", "MySQL");
        assertFalse(nullDraft.isChanged());
        assertEquals("", nullDraft.getOriginalDdl());
    }

    @Test
    public void baseTypeRewriteMapIsReadOnly() {
        try {
            SqlRewriteDraft.baseTypeRewriteMap().put("X", "Y");
            org.junit.Assert.fail("应为只读视图");
        } catch (UnsupportedOperationException expected) {
            assertTrue(SqlRewriteDraft.baseTypeRewriteMap().containsKey("VARCHAR2("));
            assertEquals("TEXT", SqlRewriteDraft.baseTypeRewriteMap().get("CLOB"));
        }
    }

    @Test
    public void draftHasNoJdbcOrConnectionCalls() throws Exception {
        DdlDraft draft = generator.generate(
                "CREATE TABLE t (a NUMBER(10,2), b VARCHAR2(64))", "Oracle", "MySQL");
        String lower = draft.getDraftDdl().toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("jdbc"));
        assertFalse(lower.contains("drivermanager"));
        assertFalse(lower.contains("getconnection"));

        // 源码级静态检查（工作目录不在模块根时跳过，不阻塞测试）
        File source = new File("src/main/java/org/dbdatasync/biz/sqlscan/DdlDraftGenerator.java");
        if (source.isFile()) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            // 只检查真实代码形态，避免命中 Javadoc 中的说明性字面量
            assertFalse(text.contains("import java.sql"));
            assertFalse(text.contains("java.sql.Connection"));
            assertFalse(text.contains("DriverManager.getConnection("));
            assertFalse(text.contains(".getConnection("));
        }
    }

    @Test
    public void wave5Wave8ScannerRegressionCorePathsStillGreen() {
        SqlCompatibilityScanner scanner = new SqlCompatibilityScanner();
        SqlScanResult r1 = scanner.scan(
                "SELECT NVL(a, 0), DECODE(x, 1, 'Y', 'N') FROM t WHERE ROWNUM <= 10", "Oracle", "MySQL");
        assertTrue(r1.isScanned());
        assertTrue(hasCode(r1, "SQL-F01"));
        assertTrue(hasCode(r1, "SQL-F02"));
        assertTrue(hasCode(r1, "SQL-C05"));

        SqlScanResult r2 = scanner.scan("SELECT SYSDATE FROM t", "Oracle", "PostgreSQL");
        SqlCompatibilityIssue sysdate = findCode(r2, "SQL-F08");
        assertNotNull(sysdate);
        assertNotNull(sysdate.getRewrittenSql());
        assertTrue(sysdate.getRewrittenSql().contains("NOW()"));

        SqlScanResult r3 = scanner.scan(
                "SELECT id FROM emp START WITH id = 1 CONNECT BY PRIOR id = mgr_id", "Oracle", "MySQL");
        SqlCompatibilityIssue connectBy = findCode(r3, "SQL-C01");
        assertNotNull(connectBy);
        assertTrue(connectBy.getRewrittenSql() == null || connectBy.getRewrittenSql().isEmpty());

        assertTrue(scanner.scan("", "Oracle", "MySQL").getIssues().isEmpty());
        assertFalse(scanner.scan("SELECT 1", "Hive", "MySQL").isScanned());
    }

    // ============ 工具 ============

    private boolean hasCode(SqlScanResult result, String code) {
        return findCode(result, code) != null;
    }

    private SqlCompatibilityIssue findCode(SqlScanResult result, String code) {
        for (SqlCompatibilityIssue issue : result.getIssues()) {
            if (code.equals(issue.getCode())) {
                return issue;
            }
        }
        return null;
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
