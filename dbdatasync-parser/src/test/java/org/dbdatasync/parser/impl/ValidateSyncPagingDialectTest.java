package org.dbdatasync.parser.impl;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 校验执行器分页方言判定的单元测试。
 * Oracle/Dameng/OceanBase 走 OFFSET/FETCH；MySQL/金仓/PG 走 LIMIT/OFFSET。
 */
public class ValidateSyncPagingDialectTest {

    @Test
    public void oracleShouldUseOffsetFetch() {
        assertTrue(ValidateSyncExecutor.isOracleFamilyType("Oracle"));
    }

    @Test
    public void damengShouldUseOffsetFetchCompatible() {
        assertTrue(ValidateSyncExecutor.isOracleFamilyType("Dameng"));
    }

    @Test
    public void oceanbaseOracleShouldUseOffsetFetch() {
        assertTrue(ValidateSyncExecutor.isOracleFamilyType("OceanBase"));
    }

    @Test
    public void mysqlShouldUseLimitOffset() {
        assertFalse(ValidateSyncExecutor.isOracleFamilyType("MySQL"));
    }

    @Test
    public void kingbaseShouldUseLimitOffset() {
        assertFalse(ValidateSyncExecutor.isOracleFamilyType("Kingbase"));
    }

    @Test
    public void postgresqlShouldUseLimitOffset() {
        assertFalse(ValidateSyncExecutor.isOracleFamilyType("PostgreSQL"));
    }

    @Test
    public void nullSafe() {
        assertFalse(ValidateSyncExecutor.isOracleFamilyType(null));
    }
}
