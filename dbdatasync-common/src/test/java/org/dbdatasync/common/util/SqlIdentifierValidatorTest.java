/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.common.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * SqlIdentifierValidator 单测：验证 Oracle CDB 公共用户 schema（C## 前缀）放行，
 * 常规标识符保持放行，任意位置含 # 的非 CDB 标识符与注入特征仍拒绝。
 */
public class SqlIdentifierValidatorTest {

    @Test
    public void cdbSchemaAllowed() {
        Assert.assertEquals("C##E2E17", SqlIdentifierValidator.validateIdentifier("C##E2E17"));
        Assert.assertEquals("c##e2e17", SqlIdentifierValidator.validateIdentifier("c##e2e17"));
        Assert.assertEquals("C##AIDBM_RO", SqlIdentifierValidator.validateIdentifier("C##AIDBM_RO"));
        Assert.assertEquals("C##E2E17", SqlIdentifierValidator.validateIdentifier(" C##E2E17 "));
    }

    @Test
    public void normalIdentifierAllowed() {
        Assert.assertEquals("t_user", SqlIdentifierValidator.validateIdentifier("t_user"));
        Assert.assertEquals("T_ORDER", SqlIdentifierValidator.validateIdentifier("T_ORDER"));
        Assert.assertEquals("用户表", SqlIdentifierValidator.validateIdentifier("用户表"));
        Assert.assertEquals("order_no", SqlIdentifierValidator.validateIdentifier("order_no"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void hashInMiddleRejected() {
        SqlIdentifierValidator.validateIdentifier("user#name");
    }

    @Test(expected = IllegalArgumentException.class)
    public void hashLeadRejected() {
        SqlIdentifierValidator.validateIdentifier("#comment");
    }

    @Test(expected = IllegalArgumentException.class)
    public void cdbBadRejected() {
        SqlIdentifierValidator.validateIdentifier("C##E2E17;DROP TABLE t");
    }

    @Test(expected = IllegalArgumentException.class)
    public void sqliRejected() {
        SqlIdentifierValidator.validateIdentifier("t_user;delete from t_user");
    }

    @Test(expected = IllegalArgumentException.class)
    public void quoteRejected() {
        SqlIdentifierValidator.validateIdentifier("'t_user'");
    }

    @Test(expected = IllegalArgumentException.class)
    public void spaceRejected() {
        SqlIdentifierValidator.validateIdentifier("t user");
    }
}
