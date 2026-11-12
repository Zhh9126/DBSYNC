package org.dbdatasync.sdk.connector.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Wave31：JDBC 读侧默认 fetchSize 回归。
 *
 * <p>此前默认 -1 从不设置 fetchSize，MySQL/SQL Server 整结果集逐行拉取、
 * 大表读取内存与往返开销大；现默认 1000，可用系统属性覆盖，-1 仍表示关闭。</p>
 */
public class Wave31FetchSizeTest {

    @Test
    public void defaultFetchSizeIsPositive() {
        DatabaseTemplate template = new DatabaseTemplate(null);
        assertTrue("默认 fetchSize 必须为正数，否则等于从不设置",
                template.getFetchSize() > 0);
        assertEquals(DatabaseTemplate.DEFAULT_FETCH_SIZE, template.getFetchSize());
    }

    @Test
    public void systemPropertyOverride() {
        String key = "dbdatasync.jdbc.fetchSize";
        String old = System.getProperty(key);
        try {
            System.setProperty(key, "5000");
            assertEquals(5000, DatabaseTemplate.resolveDefaultFetchSize());
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    @Test
    public void minusOneStillMeansDisabled() {
        DatabaseTemplate template = new DatabaseTemplate(null);
        template.setFetchSize(-1);
        assertEquals(-1, template.getFetchSize());
    }
}
