package org.dbdatasync.parser.ddl;

import org.dbdatasync.sdk.enums.DDLOperationEnum;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave23 P1：表级 DDL 识别与风险分级（CREATE / DROP / TRUNCATE / RENAME / 索引 / 约束）。
 */
public class Wave23DdlClassifierTest {

    @Test
    public void classifyTableLevelStatements() {
        assertEquals(DDLOperationEnum.CREATE_TABLE,
                DDLStatementClassifier.classifyTableLevel("CREATE TABLE t_order (id bigint primary key)"));
        assertEquals(DDLOperationEnum.CREATE_TABLE,
                DDLStatementClassifier.classifyTableLevel("create temporary table tmp_x (id int)"));
        assertEquals(DDLOperationEnum.DROP_TABLE,
                DDLStatementClassifier.classifyTableLevel("DROP TABLE IF EXISTS t_order"));
        assertEquals(DDLOperationEnum.TRUNCATE_TABLE,
                DDLStatementClassifier.classifyTableLevel("TRUNCATE TABLE t_order"));
        assertEquals(DDLOperationEnum.TRUNCATE_TABLE,
                DDLStatementClassifier.classifyTableLevel("truncate t_order"));
        assertEquals(DDLOperationEnum.RENAME_TABLE,
                DDLStatementClassifier.classifyTableLevel("RENAME TABLE t_a TO t_b"));
        assertEquals(DDLOperationEnum.CREATE_INDEX,
                DDLStatementClassifier.classifyTableLevel("CREATE UNIQUE INDEX idx_no ON t_order(order_no)"));
        assertEquals(DDLOperationEnum.DROP_INDEX,
                DDLStatementClassifier.classifyTableLevel("DROP INDEX idx_no"));
        assertEquals(DDLOperationEnum.ADD_CONSTRAINT,
                DDLStatementClassifier.classifyTableLevel("ALTER TABLE t_order ADD CONSTRAINT uk_no UNIQUE(order_no)"));
        assertEquals(DDLOperationEnum.DROP_CONSTRAINT,
                DDLStatementClassifier.classifyTableLevel("ALTER TABLE t_order DROP CONSTRAINT uk_no"));
    }

    @Test
    public void columnLevelAlterAndDmlAreLeftToLegacyParser() {
        assertNull(DDLStatementClassifier.classifyTableLevel("ALTER TABLE t_order ADD COLUMN memo varchar(200)"));
        assertNull(DDLStatementClassifier.classifyTableLevel("ALTER TABLE t_order MODIFY COLUMN amount decimal(18,2)"));
        assertNull(DDLStatementClassifier.classifyTableLevel("UPDATE t_order SET amount = 1 WHERE id = 2"));
        assertNull(DDLStatementClassifier.classifyTableLevel(""));
        assertNull(DDLStatementClassifier.classifyTableLevel(null));
    }

    @Test
    public void destructiveDdlRequiresManualConfirmation() {
        assertTrue(DDLStatementClassifier.requiresManualConfirmation(DDLOperationEnum.DROP_TABLE));
        assertTrue(DDLStatementClassifier.requiresManualConfirmation(DDLOperationEnum.TRUNCATE_TABLE));
        assertTrue(DDLStatementClassifier.requiresManualConfirmation(DDLOperationEnum.RENAME_TABLE));
        assertTrue(DDLStatementClassifier.requiresManualConfirmation(DDLOperationEnum.CREATE_TABLE));
        assertFalse(DDLStatementClassifier.requiresManualConfirmation(DDLOperationEnum.CREATE_INDEX));
        assertFalse(DDLStatementClassifier.requiresManualConfirmation(DDLOperationEnum.ALTER_ADD));
        assertFalse(DDLStatementClassifier.requiresManualConfirmation(null));
    }
}
