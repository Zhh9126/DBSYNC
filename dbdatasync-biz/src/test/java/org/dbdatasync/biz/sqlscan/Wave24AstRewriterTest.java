package org.dbdatasync.biz.sqlscan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Wave24：PL/SQL 轻量 AST 结构化改写（区分 token / 字符串 / 注释 / 语义项）。 */
public class Wave24AstRewriterTest {

    @Test
    public void parsesStructureAndRewritesMechanicalRulesOnly() {
        String sql = "CREATE OR REPLACE PROCEDURE p1 AS\n"
                + "-- NVL(SYSDATE) 注释不应被改写\n"
                + "BEGIN\n"
                + "  v := NVL(x, SYSDATE);\n"
                + "  s := 'NVL(SYSDATE) String';\n"
                + "  d := DECODE(x, 1, 'a', 'b');\n"
                + "END;";
        PlSqlAstRewriter.RewriteResult result =
                PlSqlAstRewriter.rewrite(sql, "ORACLE", "POSTGRESQL");

        assertEquals("PROCEDURE", result.getAst().getObjectType());
        assertEquals("p1", result.getAst().getObjectName());
        assertTrue(result.getAst().isHasBegin());
        assertTrue(result.getAst().isHasEnd());
        assertTrue(result.getAst().getStatementCount() >= 3);
        assertTrue(result.isChanged(sql));
        assertTrue("机械等价函数应改写", result.getRewrittenSql().contains("COALESCE(x, CURRENT_TIMESTAMP)"));
        assertTrue("字符串字面量必须保持原样", result.getRewrittenSql().contains("'NVL(SYSDATE) String'"));
        assertTrue("注释必须保持原样", result.getRewrittenSql().contains("-- NVL(SYSDATE) 注释不应被改写"));
        assertTrue("DECODE 无机械等价，应列入人工清单", result.getManualItems().contains("DECODE"));
        assertFalse(result.getRewrittenSql().contains("COALESCE(x, 1, 'a', 'b')"));
        assertTrue(result.getConfidence() > 0d && result.getConfidence() < 1d);
    }

    @Test
    public void mysqlTargetUsesNowAndOracleTargetDoesNotRewrite() {
        String sql = "CREATE FUNCTION f1 RETURN NUMBER AS BEGIN RETURN SYSDATE; END;";
        PlSqlAstRewriter.RewriteResult mysql = PlSqlAstRewriter.rewrite(sql, "oracle", "mysql");
        assertTrue(mysql.getRewrittenSql().contains("NOW()"));
        assertTrue(mysql.getRewrittenSql().contains("DECIMAL"));
        assertEquals("FUNCTION", mysql.getAst().getObjectType());

        PlSqlAstRewriter.RewriteResult dm = PlSqlAstRewriter.rewrite(sql, "oracle", "dm");
        assertFalse(dm.isChanged(sql));
        assertEquals(sql, dm.getRewrittenSql());
    }

    @Test
    public void blankInputIsSafe() {
        assertFalse(PlSqlAstRewriter.rewrite("", "oracle", "pg").isChanged(""));
        assertEquals(null, PlSqlAstRewriter.rewrite(null, "oracle", "pg").getRewrittenSql());
    }
}
