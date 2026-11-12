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
 * Wave13 B 线：DDL 变更辅助单测（JUnit4，零 Spring，纯静态）。
 *
 * <p>覆盖：ADD/MODIFY/CHANGE/RENAME/DROP 拆解字段正确、error 级 ROWID 不生成草稿、
 * 多语句、空/null 输入、解析失败降级 UNKNOWN、无映射命中返回原 SQL，
 * 以及源码无 JDBC / 运行时 DDL 类引用的静态检查。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class DdlChangeAssistTest {

    private final DdlChangeAssistService service = new DdlChangeAssistService();

    @Test
    public void addColumnVarchar2Mapping() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD COLUMN c VARCHAR2(64)", "Oracle", "MySQL");
        assertEquals(1, draft.getUnits().size());
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD", u.getOperation());
        assertEquals("c", u.getColumnName());
        assertEquals("VARCHAR2(64)", u.getSourceType());
        assertEquals("VARCHAR(64)", u.getTargetType());
        assertEquals(SqlCompatibilityIssue.LEVEL_WARN, u.getLevel());
        assertTrue(draft.isChanged());
        assertTrue(draft.getTargetDdlDraft().contains("ALTER TABLE t ADD COLUMN c VARCHAR(64)"));
        assertTrue(draft.getTargetDdlDraft().startsWith(DdlDraftGenerator.DRAFT_HEADER));
    }

    @Test
    public void modifyColumnNumberMapping() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t MODIFY COLUMN c NUMBER(10,2)", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("MODIFY", u.getOperation());
        assertEquals("c", u.getColumnName());
        assertEquals("NUMBER(10,2)", u.getSourceType());
        assertTrue("targetType 应为 DECIMAL 相关: " + u.getTargetType(),
                u.getTargetType().contains("DECIMAL"));
        assertEquals(SqlCompatibilityIssue.LEVEL_WARN, u.getLevel());
        assertTrue(draft.isChanged());
        assertTrue(draft.getTargetDdlDraft().contains("DECIMAL(10,2)"));
    }

    @Test
    public void changeColumnVarchar2Mapping() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t CHANGE COLUMN a b VARCHAR2(32)", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("CHANGE", u.getOperation());
        assertEquals("a", u.getOldName());
        assertEquals("b", u.getColumnName());
        assertEquals("VARCHAR2(32)", u.getSourceType());
        assertEquals("VARCHAR(32)", u.getTargetType());
        assertTrue(draft.isChanged());
        assertTrue(draft.getTargetDdlDraft().contains("VARCHAR(32)"));
    }

    @Test
    public void renameColumnUnit() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t RENAME COLUMN a TO b", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("RENAME", u.getOperation());
        assertEquals("a", u.getOldName());
        assertEquals("b", u.getColumnName());
        assertEquals("", u.getSourceType());
        assertEquals("", u.getTargetType());
        assertEquals(SqlCompatibilityIssue.LEVEL_INFO, u.getLevel());
        assertFalse(draft.isChanged());
    }

    @Test
    public void dropColumnUnit() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t DROP COLUMN c", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("DROP", u.getOperation());
        assertEquals("c", u.getColumnName());
        assertEquals("(drop)", u.getSourceType());
        assertEquals("(drop)", u.getTargetType());
        assertFalse(draft.isChanged());
    }

    @Test
    public void errorLevelRowidDoesNotGenerateDraft() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD COLUMN r ROWID", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals(SqlCompatibilityIssue.LEVEL_ERROR, u.getLevel());
        assertEquals("error 级类型需人工重写", u.getRiskNote());
        assertEquals(SqlCompatibilityIssue.LEVEL_ERROR, draft.getLevel());
        assertEquals("", draft.getTargetDdlDraft());
        assertFalse(draft.isChanged());
        assertTrue(draft.getNote().contains("需人工重写"));
    }

    @Test
    public void multiStatementTwoUnits() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD COLUMN a VARCHAR2(10); ALTER TABLE t DROP COLUMN b;", "Oracle", "MySQL");
        assertEquals(2, draft.getUnits().size());
        assertEquals("ADD", draft.getUnits().get(0).getOperation());
        assertEquals("DROP", draft.getUnits().get(1).getOperation());
        assertTrue(draft.getTargetDdlDraft().contains("ALTER TABLE t ADD COLUMN a VARCHAR(10)"));
        assertTrue(draft.getTargetDdlDraft().contains("ALTER TABLE t DROP COLUMN b"));
    }

    @Test
    public void blankInputNoChange() {
        DdlChangeDraft draft = service.assist("   ", "Oracle", "MySQL");
        assertTrue(draft.getUnits().isEmpty());
        assertFalse(draft.isChanged());
        assertTrue(draft.getNote().contains("无 DDL 变更识别"));
    }

    @Test
    public void nullInputNoChange() {
        DdlChangeDraft draft = service.assist(null, "Oracle", "MySQL");
        assertTrue(draft.getUnits().isEmpty());
        assertFalse(draft.isChanged());
        assertTrue(draft.getNote().contains("无 DDL 变更识别"));
    }

    @Test
    public void unknownParseFallsBack() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t SOMETHING_WEIRD x y z", "Oracle", "MySQL");
        boolean hasUnknown = false;
        for (DdlChangeUnit u : draft.getUnits()) {
            if ("UNKNOWN".equals(u.getOperation())) {
                hasUnknown = true;
                assertTrue(u.getRiskNote().contains("无法解析"));
            }
        }
        assertTrue(hasUnknown);
    }

    @Test
    public void noMappingHitReturnsOriginalSql() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD COLUMN c INT", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("INT", u.getSourceType());
        assertEquals("INT", u.getTargetType());
        assertEquals(SqlCompatibilityIssue.LEVEL_INFO, u.getLevel());
        assertFalse(draft.isChanged());
        assertEquals(SqlCompatibilityIssue.LEVEL_INFO, draft.getLevel());
        assertEquals("ALTER TABLE t ADD COLUMN c INT", draft.getTargetDdlDraft());
        assertTrue(draft.getNote().contains("无映射命中"));
    }

    @Test
    public void sourceFilesHaveNoJdbcOrRuntimeDdlReferences() throws Exception {
        String[] files = {
                "src/main/java/org/dbdatasync/biz/sqlscan/DdlChangeUnit.java",
                "src/main/java/org/dbdatasync/biz/sqlscan/DdlChangeDraft.java",
                "src/main/java/org/dbdatasync/biz/sqlscan/DdlChangeSplitter.java",
                "src/main/java/org/dbdatasync/biz/sqlscan/DdlChangeAssistService.java"
        };
        for (String path : files) {
            File f = new File(path);
            if (!f.isFile()) {
                // 工作目录不在模块根时跳过，不阻塞测试
                continue;
            }
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertFalse(path + " 不应引用 java.sql", text.contains("java.sql"));
            assertFalse(path + " 不应调用 getConnection(", text.contains("getConnection("));
            assertFalse(path + " 不应引用 writerDDL", text.contains("writerDDL"));
            assertFalse(path + " 不应引用 GeneralBufferActuator", text.contains("GeneralBufferActuator"));
            assertFalse(path + " 不应引用 DdlParserImpl", text.contains("DdlParserImpl"));
        }
    }

    // ===== Wave15：复合 ALTER 子句识别（不再 UNKNOWN） =====

    @Test
    public void addNamedForeignKeyConstraint() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD CONSTRAINT fk_x FOREIGN KEY (a) REFERENCES t2(b)", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD_CONSTRAINT", u.getOperation());
        assertEquals("fk_x", u.getObjectName());
        assertEquals("", u.getColumnName());
        assertEquals(SqlCompatibilityIssue.LEVEL_INFO, u.getLevel());
        assertTrue(u.getRiskNote().contains("FOREIGN KEY"));
        assertFalse("复合子句不应退化为 UNKNOWN", "UNKNOWN".equals(u.getOperation()));
    }

    @Test
    public void addUnnamedPrimaryKey() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD PRIMARY KEY (a, b)", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD_CONSTRAINT", u.getOperation());
        assertEquals("", u.getObjectName());
        assertTrue(u.getRiskNote().contains("PRIMARY KEY"));
        assertEquals(SqlCompatibilityIssue.LEVEL_INFO, u.getLevel());
    }

    @Test
    public void addUnnamedUniqueConstraint() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD UNIQUE (a)", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD_CONSTRAINT", u.getOperation());
        assertTrue(u.getRiskNote().contains("UNIQUE"));
    }

    @Test
    public void addNamedIndex() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD INDEX idx_name (a, b)", "MySQL", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD_INDEX", u.getOperation());
        assertEquals("idx_name", u.getObjectName());
    }

    @Test
    public void addUniqueIndexNamed() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD UNIQUE INDEX idx2 (c)", "MySQL", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD_INDEX", u.getOperation());
        assertEquals("idx2", u.getObjectName());
        assertTrue(u.getRiskNote().contains("唯一"));
    }

    @Test
    public void addKeyMysqlStyle() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD KEY k_name (d)", "MySQL", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD_INDEX", u.getOperation());
        assertEquals("k_name", u.getObjectName());
        assertTrue(u.getRiskNote().contains("KEY"));
    }

    @Test
    public void dropNamedConstraint() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t DROP CONSTRAINT fk_x", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("DROP_CONSTRAINT", u.getOperation());
        assertEquals("fk_x", u.getObjectName());
    }

    @Test
    public void dropPrimaryKey() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t DROP PRIMARY KEY", "MySQL", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("DROP_PRIMARY_KEY", u.getOperation());
        assertEquals("", u.getObjectName());
    }

    @Test
    public void dropNamedIndex() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t DROP INDEX idx_name", "MySQL", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("DROP_INDEX", u.getOperation());
        assertEquals("idx_name", u.getObjectName());
    }

    @Test
    public void setColumnDefault() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ALTER COLUMN c SET DEFAULT 0", "MySQL", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("SET_DEFAULT", u.getOperation());
        assertEquals("c", u.getColumnName());
        assertEquals(SqlCompatibilityIssue.LEVEL_INFO, u.getLevel());
    }

    @Test
    public void dropColumnDefault() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ALTER COLUMN c DROP DEFAULT", "MySQL", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("DROP_DEFAULT", u.getOperation());
        assertEquals("c", u.getColumnName());
    }

    @Test
    public void compositeNotMisclassifiedAsColumnAdd() {
        // 优先级校验：ADD CONSTRAINT 不能误判为通用 ADD 列
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD CONSTRAINT pk_x PRIMARY KEY (a)", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD_CONSTRAINT", u.getOperation());
        assertEquals("", u.getColumnName());
        assertFalse("不应误判为列 ADD", "ADD".equals(u.getOperation()));
    }

    @Test
    public void compositeUnitKeepsOriginalSqlUnchanged() {
        // 复合子句无类型映射，应返回原 SQL（不自动改写）
        String sql = "ALTER TABLE t ADD CONSTRAINT fk_x FOREIGN KEY (a) REFERENCES t2(b)";
        DdlChangeDraft draft = service.assist(sql, "Oracle", "MySQL");
        assertFalse(draft.isChanged());
        assertEquals(sql, draft.getTargetDdlDraft());
    }

    // ===== Wave16：分区表 PARTITION 子句识别（不再 UNKNOWN） =====

    @Test
    public void addPartitionRecognized() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD PARTITION p1 VALUES LESS THAN (1000)", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD_PARTITION", u.getOperation());
        assertEquals("p1", u.getObjectName());
        assertEquals(SqlCompatibilityIssue.LEVEL_INFO, u.getLevel());
        assertFalse("ADD PARTITION 不应退化为 UNKNOWN", "UNKNOWN".equals(u.getOperation()));
    }

    @Test
    public void dropPartitionRecognized() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t DROP PARTITION p1", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("DROP_PARTITION", u.getOperation());
        assertEquals("p1", u.getObjectName());
    }

    @Test
    public void truncatePartitionRecognized() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t TRUNCATE PARTITION p1", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("TRUNCATE_PARTITION", u.getOperation());
        assertEquals("p1", u.getObjectName());
    }

    @Test
    public void attachPartitionRecognized() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ATTACH PARTITION p1 FOR VALUES IN (1, 2)", "PostgreSQL", "PostgreSQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ATTACH_PARTITION", u.getOperation());
        assertEquals("p1", u.getObjectName());
    }

    @Test
    public void detachPartitionRecognized() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t DETACH PARTITION p1", "PostgreSQL", "PostgreSQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("DETACH_PARTITION", u.getOperation());
        assertEquals("p1", u.getObjectName());
    }

    @Test
    public void addPartitionIfNotExistsCapturesNameOnly() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD PARTITION IF NOT EXISTS p1 VALUES LESS THAN (2000)", "PostgreSQL", "PostgreSQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD_PARTITION", u.getOperation());
        assertEquals("p1", u.getObjectName());
        assertFalse("不应把 IF 误捕获为分区名", "IF".equals(u.getObjectName()));
    }

    @Test
    public void dropPartitionIfExistsCapturesNameOnly() {
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t DROP PARTITION IF EXISTS p1", "PostgreSQL", "PostgreSQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("DROP_PARTITION", u.getOperation());
        assertEquals("p1", u.getObjectName());
        assertFalse("不应把 IF 误捕获为分区名", "IF".equals(u.getObjectName()));
    }

    @Test
    public void addPartitionNotMisclassifiedAsColumnAdd() {
        // 优先级校验：ADD PARTITION 不能误判为通用 ADD 列（列名 PARTITION）
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD PARTITION p1 VALUES LESS THAN (MAXVALUE)", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("ADD_PARTITION", u.getOperation());
        assertFalse("不应误判为列 ADD", "ADD".equals(u.getOperation()));
        assertEquals("", u.getColumnName());
    }

    @Test
    public void dropPartitionNotMisclassifiedAsColumnDrop() {
        // 优先级校验：DROP PARTITION 不能误判为通用 DROP 列（列名 PARTITION）
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t DROP PARTITION p1", "Oracle", "MySQL");
        DdlChangeUnit u = draft.getUnits().get(0);
        assertEquals("DROP_PARTITION", u.getOperation());
        assertFalse("不应误判为列 DROP", "DROP".equals(u.getOperation()));
        assertEquals("", u.getColumnName());
    }

    @Test
    public void partitionUnitReturnsOriginalSqlUnchanged() {
        // 分区子句无类型映射，应返回原 SQL（不自动改写）
        String sql = "ALTER TABLE t DROP PARTITION p1";
        DdlChangeDraft draft = service.assist(sql, "Oracle", "MySQL");
        assertFalse(draft.isChanged());
        assertEquals(sql, draft.getTargetDdlDraft());
    }

    @Test
    public void partitionAndColumnAddNoCrossContamination() {
        // 多语句：ADD COLUMN（列操作）与 ADD PARTITION（分区操作）互不误判
        DdlChangeDraft draft = service.assist(
                "ALTER TABLE t ADD COLUMN c VARCHAR2(10); ALTER TABLE t ADD PARTITION p1 VALUES LESS THAN (100)",
                "Oracle", "MySQL");
        assertEquals(2, draft.getUnits().size());
        DdlChangeUnit colUnit = draft.getUnits().get(0);
        DdlChangeUnit partUnit = draft.getUnits().get(1);
        assertEquals("ADD", colUnit.getOperation());
        assertEquals("c", colUnit.getColumnName());
        assertEquals("ADD_PARTITION", partUnit.getOperation());
        assertEquals("p1", partUnit.getObjectName());
    }
}
