package org.dbdatasync.biz.sqlscan;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SQL 兼容度扫描引擎单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>Oracle→MySQL：NVL/DECODE/ROWNUM/CONNECT BY/MINUS/LISTAGG/类型族</li>
 *   <li>MySQL→PG：IFNULL/GROUP_CONCAT/ILIKE/FIND_IN_SET/REPLACE/ON DUPLICATE</li>
 *   <li>Oracle→达梦：同族差异（叠加 LIMIT 提示）</li>
 *   <li>PG→MySQL：DISTINCT ON/GENERATE_SERIES/:: 类型转换</li>
 *   <li>降级：无法解析的 PL/SQL 给 unparseable 提示</li>
 *   <li>方言未知降级</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
public class SqlCompatibilityScannerTest {

    private final SqlCompatibilityScanner scanner = new SqlCompatibilityScanner();

    // ============ Oracle → MySQL ============

    @Test
    public void oracleToMySql_nvlDecodeRownum() {
        String sql = "SELECT NVL(a.amount, 0) AS amt, DECODE(a.status, 1, 'Y', 'N') AS st FROM t a WHERE ROWNUM <= 10";
        SqlScanResult result = scanner.scan(sql, "Oracle", "MySQL");
        assertTrue(result.isScanned());
        assertTrue(result.getStatementCount() >= 1);
        assertTrue(hasCode(result, "SQL-F01"));   // NVL
        assertTrue(hasCode(result, "SQL-F02"));   // DECODE
        assertTrue(hasCode(result, "SQL-C05"));   // ROWNUM（结构族）
        // 不误报：无 target 方言支持项
        assertFalse(hasCode(result, "SQL-F08"));  // SYSDATE 未出现
        // ROWNUM 只报结构族一处，不重复命中函数族（SQL-F17 已从函数表删除）
        assertEquals(1, countCode(result, "rownum"));
    }

    @Test
    public void oracleToMySql_connectByMinusListAgg() {
        String sql = "SELECT id FROM emp START WITH id = 1 CONNECT BY PRIOR id = mgr_id;"
                + "SELECT * FROM t1 MINUS SELECT * FROM t2;"
                + "SELECT deptno, LISTAGG(name, ',') FROM emp GROUP BY deptno";
        SqlScanResult result = scanner.scan(sql, "Oracle", "MySQL");
        assertTrue(hasCode(result, "SQL-C01"));   // CONNECT BY 结构
        assertTrue(hasCode(result, "SQL-C03"));   // MINUS
        assertTrue(hasCode(result, "SQL-F14"));   // LISTAGG
        assertTrue(hasCode(result, "SQL-C02"));   // START WITH
        assertTrue(result.getErrorCount() > 0);
    }

    @Test
    public void oracleToMySql_types() {
        String sql = "SELECT CAST(a AS VARCHAR2(10)), b FROM t WHERE a = EMPTY_CLOB()";
        SqlScanResult result = scanner.scan(sql, "Oracle", "MySQL");
        assertTrue(hasCode(result, "SQL-T02"));   // VARCHAR2（含带长度形态）
        assertTrue(hasCode(result, "SQL-F20"));   // EMPTY_CLOB
        // 类型带长度形态 varchar2(10) 也必须命中，防类型匹配过窄回归
        assertTrue(countCode(result, "SQL-T02") >= 1);
    }

    @Test
    public void oracleToPg_sysdateAddMonths() {
        String sql = "SELECT SYSDATE, ADD_MONTHS(d, 1) FROM t";
        SqlScanResult result = scanner.scan(sql, "Oracle", "PostgreSQL");
        assertTrue(hasCode(result, "SQL-F08"));   // SYSDATE
        assertTrue(hasCode(result, "SQL-F10"));   // ADD_MONTHS
    }

    // ============ MySQL → PG ============

    @Test
    public void mysqlToPg_ifnullGroupConcat() {
        String sql = "SELECT IFNULL(a, 0), GROUP_CONCAT(name) FROM t WHERE FIND_IN_SET('x', tags)";
        SqlScanResult result = scanner.scan(sql, "MySQL", "PostgreSQL");
        assertTrue(hasCode(result, "SQL-F31"));   // IFNULL
        assertTrue(hasCode(result, "SQL-F33"));   // GROUP_CONCAT
        assertTrue(hasCode(result, "SQL-F40"));   // FIND_IN_SET
    }

    @Test
    public void mysqlToPg_onDuplicateKey() {
        String sql = "INSERT INTO t(id, v) VALUES (1, 2) ON DUPLICATE KEY UPDATE v = VALUES(v)";
        SqlScanResult result = scanner.scan(sql, "MySQL", "PostgreSQL");
        assertTrue(hasCode(result, "SQL-C07"));   // ON DUPLICATE KEY
    }

    // ============ Oracle → 达梦 ============

    @Test
    public void oracleToDm_oracleFamily() {
        String sql = "SELECT NVL(a, 0) FROM t WHERE ROWNUM <= 5 LIMIT 10";
        SqlScanResult result = scanner.scan(sql, "Oracle", "DM");
        // 达梦与 Oracle 同族：NVL/ROWNUM 为兼容模式可用项不报（设计如此，同族不制造噪音），
        // LIMIT 提示（达梦兼容模式可用，info）命中
        assertTrue(result.isScanned());
        assertTrue(hasCode(result, "SQL-C06"));   // LIMIT（info）
        assertFalse(hasCode(result, "SQL-F01"));  // 同族不报 NVL
        assertFalse(hasCode(result, "SQL-C05"));  // 同族不报 ROWNUM 结构
    }

    // ============ PG → MySQL ============

    @Test
    public void pgToMySql_distinctOnGenerateSeries() {
        String sql = "SELECT DISTINCT ON (dept) id FROM emp ORDER BY dept, id;"
                + "SELECT * FROM generate_series(1, 10);"
                + "SELECT now()::timestamp;";
        SqlScanResult result = scanner.scan(sql, "PostgreSQL", "MySQL");
        assertTrue(hasCode(result, "SQL-F66"));   // DISTINCT ON
        assertTrue(hasCode(result, "SQL-F65"));   // GENERATE_SERIES
        assertTrue(hasCode(result, "SQL-F62"));   // :: 类型转换
    }

    @Test
    public void pgToMySql_ilike() {
        String sql = "SELECT * FROM t WHERE name ILIKE '%abc%'";
        SqlScanResult result = scanner.scan(sql, "PostgreSQL", "MySQL");
        assertTrue(hasCode(result, "SQL-F63"));   // ILIKE
    }

    // ============ 降级路径 ============

    @Test
    public void unparseable_sql_getsWarning() {
        String sql = "DECLARE v NUMBER; BEGIN SELECT 1 INTO v FROM dual; END;";
        SqlScanResult result = scanner.scan(sql, "Oracle", "MySQL");
        // 无法解析 → 至少一个 unparseable 提示（warn）
        assertTrue(hasCode(result, "sql-unparseable"));
        assertTrue(result.getWarnCount() > 0);
    }

    @Test
    public void unknownDialect_degrades() {
        SqlScanResult result = scanner.scan("SELECT 1", "Hive", "MySQL");
        assertFalse(result.isScanned());
        assertTrue(hasCode(result, "sql-unsupported-dialect"));
        assertEquals(1, result.getInfoCount());
    }

    @Test
    public void emptySql_safe() {
        SqlScanResult result = scanner.scan("", "Oracle", "MySQL");
        assertTrue(result.isScanned());
        assertEquals(0, result.getStatementCount());
        assertEquals(0, result.getIssues().size());
    }

    // ============ 改写草稿（P1 语法转换辅助） ============

    @Test
    public void rewriteDraft_warnGeneratesRewrittenSql() {
        String sql = "SELECT NVL(a.amount, 0), SYSDATE FROM t";
        SqlScanResult result = scanner.scan(sql, "Oracle", "MySQL");
        SqlCompatibilityIssue nvl = findCode(result, "SQL-F01");
        assertNotNull(nvl);
        // warn 级 NVL → COALESCE 草稿生成
        assertNotNull(nvl.getRewrittenSql());
        assertTrue(nvl.getRewrittenSql().contains("COALESCE(a.amount, 0)"));
        assertTrue(nvl.getRewrittenSql().contains("NOW()"));
    }

    @Test
    public void rewriteDraft_errorLevel_keepsEmpty() {
        String sql = "SELECT id FROM emp START WITH id = 1 CONNECT BY PRIOR id = mgr_id";
        // CONNECT BY 是 error（结构族） → rewrite 草稿留空需人工
        SqlScanResult result = scanner.scan(sql, "Oracle", "MySQL");
        SqlCompatibilityIssue issue = findCode(result, "SQL-C01");
        assertNotNull(issue);
        assertEquals(SqlCompatibilityIssue.LEVEL_ERROR, issue.getLevel());
        assertTrue(issue.getRewrittenSql() == null || issue.getRewrittenSql().isEmpty());
    }

    @Test
    public void rewriteDraft_unchanged_keepsNull() {
        // 无机械映射项（info 不需改）→ 草稿为空
        SqlScanResult result = scanner.scan("SELECT CEIL(a) FROM t", "Oracle", "MySQL");
        SqlCompatibilityIssue ceil = findCode(result, "SQL-F80");
        assertNotNull(ceil);
        assertEquals(SqlCompatibilityIssue.LEVEL_INFO, ceil.getLevel());
        assertTrue(ceil.getRewrittenSql() == null);
    }

    @Test
    public void rewriteDraft_oracleToPg_generates() {
        String sql = "SELECT SYSDATE, ADD_MONTHS(d, 1) FROM t";
        SqlScanResult result = scanner.scan(sql, "Oracle", "PostgreSQL");
        SqlCompatibilityIssue sysdate = findCode(result, "SQL-F08");
        assertNotNull(sysdate);
        assertNotNull(sysdate.getRewrittenSql());
        assertTrue(sysdate.getRewrittenSql().contains("NOW()"));
    }

    @Test
    public void rewriteDraft_pgToMySql_generates() {
        // ILIKE 规则级别为 error（大小写不敏感语义需人工确认），草稿留空；
        // 用 ::timestamp（SQL-F62，warn 可机械改）验证草稿生成
        String sql = "SELECT now()::timestamp FROM t";
        SqlScanResult result = scanner.scan(sql, "PostgreSQL", "MySQL");
        SqlCompatibilityIssue cast = findCode(result, "SQL-F62");
        assertNotNull(cast);
        assertEquals(SqlCompatibilityIssue.LEVEL_WARN, cast.getLevel());
        assertNotNull(cast.getRewrittenSql());
        assertFalse(cast.getRewrittenSql().contains("::"));
    }

    // ============ Wave17：PL/SQL / 存储过程对象识别 ============

    @Test
    public void plsqlProcedureDetected() {
        String sql = "CREATE OR REPLACE PROCEDURE p_audit (p_id IN NUMBER) AS BEGIN NULL; END;";
        SqlScanResult result = scanner.scan(sql, "Oracle", "MySQL");
        assertTrue("应识别 PL/SQL 存储过程", hasCode(result, "SQL-P01"));
        SqlCompatibilityIssue issue = findCode(result, "SQL-P01");
        assertNotNull(issue);
        assertEquals(SqlCompatibilityIssue.LEVEL_ERROR, issue.getLevel());
        assertEquals("plsql", issue.getCategory());
        assertEquals("p_audit", issue.getElement());
        assertTrue(issue.getMessage().contains("p_audit"));
    }

    @Test
    public void plsqlFunctionAndTriggerDetected() {
        String sql = "CREATE OR REPLACE FUNCTION f_calc RETURN NUMBER IS BEGIN RETURN 1; END;"
                + "CREATE OR REPLACE TRIGGER trg_a BEFORE INSERT ON t FOR EACH ROW BEGIN NULL; END;";
        SqlScanResult result = scanner.scan(sql, "Oracle", "MySQL");
        assertTrue(hasCode(result, "SQL-P02"));
        assertTrue(hasCode(result, "SQL-P03"));
    }

    @Test
    public void plsqlPackageAndTypeDetected() {
        String sql = "CREATE OR REPLACE PACKAGE pkg_a AS PROCEDURE p1; END pkg_a;"
                + "CREATE OR REPLACE PACKAGE BODY pkg_a AS PROCEDURE p1 IS BEGIN NULL; END; END pkg_a;"
                + "CREATE OR REPLACE TYPE t_obj AS OBJECT (id NUMBER);";
        SqlScanResult result = scanner.scan(sql, "Oracle", "MySQL");
        assertTrue(hasCode(result, "SQL-P04"));
        assertTrue(hasCode(result, "SQL-P05"));
        assertTrue(hasCode(result, "SQL-P06"));
    }

    @Test
    public void plsqlNoFalsePositiveOnPlainSelect() {
        SqlScanResult result = scanner.scan("SELECT 1 FROM t", "Oracle", "MySQL");
        assertFalse(hasCode(result, "SQL-P01"));
        assertFalse(hasCode(result, "SQL-P02"));
        assertFalse(hasCode(result, "SQL-P04"));
    }

    // ============ 工具 ============

    private boolean hasCode(SqlScanResult result, String code) {
        return countCode(result, code) > 0;
    }

    private SqlCompatibilityIssue findCode(SqlScanResult result, String code) {
        if (result.getIssues() == null) {
            return null;
        }
        for (SqlCompatibilityIssue issue : result.getIssues()) {
            if (code.equals(issue.getCode())) {
                return issue;
            }
        }
        return null;
    }

    /** 统计同一 code（元素级）出现次数，用于防重复命中回归 */
    private int countCode(SqlScanResult result, String elementLowerOrCode) {
        if (result.getIssues() == null) {
            return 0;
        }
        int count = 0;
        for (SqlCompatibilityIssue issue : result.getIssues()) {
            if (elementLowerOrCode.equals(issue.getCode())) {
                count++;
            } else if (elementLowerOrCode.equalsIgnoreCase(issue.getElement())) {
                count++;
            }
        }
        return count;
    }
}
