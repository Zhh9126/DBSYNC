package org.dbdatasync.biz.sqlscan;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave10 T04：扫描对象扩展单测（保守深度）。
 *
 * <p>3 类 CREATE 对象定义（PROCEDURE / TRIGGER / VIEW，另含 FUNCTION）各 ≥1 条命中，
 * PL/SQL 块降级 1 条；断言不抛异常、至少命中类型/函数/结构族或 {@code sql-unparseable}、
 * 降级 issue 追加「仅文本降级扫描 / 需人工确认」；既有 17 项扫描器回归由全量
 * {@code mvn -o -pl dbdatasync-biz -am test} 覆盖（本类另跑核心路径 sanity）。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class SqlObjectDefinitionExpanderTest {

    private final SqlObjectDefinitionExpander expander = new SqlObjectDefinitionExpander();

    private static final String PROCEDURE =
            "CREATE OR REPLACE PROCEDURE p_sync AS\n"
                    + "  v_cnt NUMBER(10);\n"
                    + "BEGIN\n"
                    + "  SELECT COUNT(*) INTO v_cnt FROM t WHERE ROWNUM <= 10;\n"
                    + "  DBMS_OUTPUT.PUT_LINE(v_cnt);\n"
                    + "END;";

    private static final String FUNCTION =
            "CREATE FUNCTION f_amount(p_id NUMBER) RETURN NUMBER AS\n"
                    + "  v NUMBER;\n"
                    + "BEGIN\n"
                    + "  SELECT NVL(amount, 0) INTO v FROM t WHERE id = p_id;\n"
                    + "  RETURN v;\n"
                    + "END;";

    private static final String TRIGGER =
            "CREATE OR REPLACE TRIGGER trg_t BEFORE INSERT ON t FOR EACH ROW\n"
                    + "BEGIN\n"
                    + "  :NEW.name := UPPER(:NEW.name);\n"
                    + "END;";

    private static final String VIEW =
            "CREATE OR REPLACE VIEW v_t AS SELECT NVL(a, 0) AS a FROM t WHERE ROWNUM <= 100";

    private static final String PLSQL_BLOCK =
            "DECLARE v NUMBER; BEGIN SELECT 1 INTO v FROM dual; END;";

    @Test
    public void procedureDefinitionProducesIssues() {
        SqlScanResult result = expander.expandAndScan(PROCEDURE, "Oracle", "MySQL");
        assertNotNull(result);
        assertTrue(result.getStatementCount() >= 1);
        assertTrue(hasRuleHitOrUnparseable(result));
        assertTrue(hasCode(result, "sql-unparseable"));
        assertTrue(fallbackIssuesCarryNote(result));
    }

    @Test
    public void functionDefinitionProducesIssues() {
        SqlScanResult result = expander.expandAndScan(FUNCTION, "Oracle", "MySQL");
        assertNotNull(result);
        assertTrue(hasRuleHitOrUnparseable(result));
    }

    @Test
    public void triggerDefinitionProducesIssues() {
        SqlScanResult result = expander.expandAndScan(TRIGGER, "Oracle", "MySQL");
        assertNotNull(result);
        assertTrue(hasRuleHitOrUnparseable(result));
        assertTrue(hasCode(result, "sql-unparseable"));
        assertTrue(fallbackIssuesCarryNote(result));
    }

    @Test
    public void viewDefinitionProducesIssues() {
        SqlScanResult result = expander.expandAndScan(VIEW, "Oracle", "MySQL");
        assertNotNull(result);
        assertTrue(hasRuleHitOrUnparseable(result));
        // 视图定义内 NVL / ROWNUM 至少命中函数族或结构族之一
        assertTrue(hasCode(result, "SQL-F01") || hasCode(result, "SQL-C05")
                || hasCode(result, "sql-unparseable"));
    }

    @Test
    public void plsqlBlockDegradesWithoutException() {
        SqlScanResult result = expander.expandAndScan(PLSQL_BLOCK, "Oracle", "MySQL");
        assertNotNull(result);
        assertTrue(result.getWarnCount() > 0);
        assertTrue(hasCode(result, "sql-unparseable"));
    }

    @Test
    public void multipleObjectDefinitionsMergedAndNeverThrow() {
        String combined = PROCEDURE + "\n" + FUNCTION + "\n" + TRIGGER + "\n" + VIEW;
        SqlScanResult result = expander.expandAndScan(combined, "Oracle", "MySQL");
        assertNotNull(result);
        assertTrue(result.getStatementCount() >= 4);
        assertTrue(result.getIssues().size() >= 4);
        assertTrue(hasRuleHitOrUnparseable(result));
        assertTrue(result.getErrorCount() + result.getWarnCount() + result.getInfoCount()
                == result.getIssues().size());
    }

    @Test
    public void blankAndUnknownDialectAreSafe() {
        SqlScanResult blank = expander.expandAndScan("   ", "Oracle", "MySQL");
        assertNotNull(blank);
        assertTrue(blank.getIssues().isEmpty());
        assertFalse(blank.isScanned());

        SqlScanResult unknown = expander.expandAndScan(VIEW, "Hive", "MySQL");
        assertNotNull(unknown);
        assertTrue(hasCode(unknown, "sql-unsupported-dialect"));
        assertTrue(fallbackIssuesCarryNote(unknown));
    }

    @Test
    public void wave5Scanner17CasesCorePathsUnaffected() {
        SqlCompatibilityScanner scanner = new SqlCompatibilityScanner();
        SqlScanResult r1 = scanner.scan("SELECT NVL(a, 0) FROM t WHERE ROWNUM <= 10", "Oracle", "MySQL");
        assertTrue(hasCode(r1, "SQL-F01"));
        assertTrue(hasCode(r1, "SQL-C05"));
        assertTrue(scanner.scan("", "Oracle", "MySQL").getIssues().isEmpty());
        assertFalse(scanner.scan("SELECT 1", "Hive", "MySQL").isScanned());
        SqlScanResult r2 = scanner.scan("SELECT SYSDATE FROM t", "Oracle", "PostgreSQL");
        assertNotNull(findCode(r2, "SQL-F08").getRewrittenSql());
    }

    // ============ 工具 ============

    private boolean hasRuleHitOrUnparseable(SqlScanResult result) {
        if (result.getIssues().isEmpty()) {
            return false;
        }
        for (SqlCompatibilityIssue issue : result.getIssues()) {
            String code = issue.getCode() == null ? "" : issue.getCode();
            if (code.startsWith("SQL-F") || code.startsWith("SQL-C")
                    || code.startsWith("SQL-T") || code.startsWith("SQL-O")
                    || "sql-unparseable".equals(code)
                    || "sql-unsupported-dialect".equals(code)) {
                return true;
            }
        }
        return false;
    }

    private boolean fallbackIssuesCarryNote(SqlScanResult result) {
        for (SqlCompatibilityIssue issue : result.getIssues()) {
            String code = issue.getCode() == null ? "" : issue.getCode();
            if ("sql-unparseable".equals(code) || "sql-unsupported-dialect".equals(code)) {
                String message = issue.getMessage() == null ? "" : issue.getMessage();
                String suggestion = issue.getSuggestion() == null ? "" : issue.getSuggestion();
                if (message.contains(SqlObjectDefinitionExpander.FALLBACK_NOTE)
                        && suggestion.contains(SqlObjectDefinitionExpander.FALLBACK_NOTE)) {
                    return true;
                }
            }
        }
        return false;
    }

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
}
