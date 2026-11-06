/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 迁移编排阶段工具单元测试。
 */
public class MigrationStageUtilTest {

    @Test
    public void testNormalizeUnknownFallback() {
        assertEquals("schedule", MigrationStageUtil.normalize(null));
        assertEquals("schedule", MigrationStageUtil.normalize(""));
        assertEquals("schedule", MigrationStageUtil.normalize("  "));
        assertEquals("schedule", MigrationStageUtil.normalize("bogus"));
        // 合法但带大小写/空格：归一化后返回合法阶段（不是回落）
        assertEquals("full", MigrationStageUtil.normalize("FULL "));
    }

    @Test
    public void testNormalizeCaseAndTrim() {
        assertEquals("full", MigrationStageUtil.normalize(" full "));
        assertEquals("increment", MigrationStageUtil.normalize("INCREMENT"));
        assertEquals("validate", MigrationStageUtil.normalize(" Validate "));
        assertEquals("switchover", MigrationStageUtil.normalize("switchover"));
    }

    @Test
    public void testIsValid() {
        assertTrue(MigrationStageUtil.isValid("schedule"));
        assertTrue(MigrationStageUtil.isValid(" full "));
        assertTrue(MigrationStageUtil.isValid("INCREMENT"));
        assertFalse(MigrationStageUtil.isValid(null));
        assertFalse(MigrationStageUtil.isValid(""));
        assertFalse(MigrationStageUtil.isValid("bogus"));
    }

    @Test
    public void testAdvanceChain() {
        assertEquals("full", MigrationStageUtil.advance("schedule"));
        assertEquals("increment", MigrationStageUtil.advance("full"));
        assertEquals("validate", MigrationStageUtil.advance("increment"));
        assertEquals("switchover", MigrationStageUtil.advance("validate"));
        // 末位不越界
        assertEquals("switchover", MigrationStageUtil.advance("switchover"));
        // 非法输入回落 schedule 后前进
        assertEquals("full", MigrationStageUtil.advance("bogus"));
        assertEquals("full", MigrationStageUtil.advance(null));
    }

    @Test
    public void testAdvanceIsStableStep() {
        String cur = "schedule";
        String[] expected = {"full", "increment", "validate", "switchover"};
        for (String exp : expected) {
            cur = MigrationStageUtil.advance(cur);
            assertEquals(exp, cur);
        }
    }

    @Test
    public void testLabel() {
        assertEquals("编排就绪", MigrationStageUtil.label("schedule"));
        assertEquals("全量迁移", MigrationStageUtil.label("full"));
        assertEquals("增量追平", MigrationStageUtil.label("increment"));
        assertEquals("一致性校验", MigrationStageUtil.label("validate"));
        assertEquals("切换", MigrationStageUtil.label("switchover"));
        assertEquals("编排就绪", MigrationStageUtil.label("bogus"));
        assertEquals("编排就绪", MigrationStageUtil.label(null));
    }

    @Test
    public void testChainOrder() {
        assertEquals(5, MigrationStageUtil.all().size());
        assertSame(MigrationStageUtil.STAGES.get(0), MigrationStageUtil.all().iterator().next());
        assertEquals("schedule", MigrationStageUtil.STAGES.get(0));
        assertEquals("switchover", MigrationStageUtil.STAGES.get(4));
    }
}
