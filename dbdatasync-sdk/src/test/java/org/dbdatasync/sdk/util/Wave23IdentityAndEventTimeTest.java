package org.dbdatasync.sdk.util;

import org.dbdatasync.sdk.SdkException;
import org.dbdatasync.sdk.model.ChangedOffset;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.Table;
import org.junit.After;
import org.junit.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Wave23 P0-A / P1 离线测试：源端事件时间标记、无主键表行标识退化策略。
 */
public class Wave23IdentityAndEventTimeTest {

    @After
    public void tearDown() {
        RowIdentityResolver.uninstallFallbackProvider();
        RowIdentityResolver.setFallbackEnabled(true);
    }

    @Test
    public void sourceEventTimeIsExplicitlyMarked() {
        ChangedOffset offset = new ChangedOffset();
        assertEquals(ChangedOffset.EVENT_TIME_UNKNOWN, offset.getEventTimeSource());
        assertFalse(offset.isSourceEventTime());

        offset.markSourceEventTime(1700000000000L);
        assertEquals(1700000000000L, offset.getEventTime());
        assertEquals(ChangedOffset.EVENT_TIME_SOURCE, offset.getEventTimeSource());
        assertTrue(offset.isSourceEventTime());

        // 估算时间不得冒充源端时间
        offset.markEstimatedEventTime(1700000009999L);
        assertEquals(ChangedOffset.EVENT_TIME_ESTIMATED, offset.getEventTimeSource());
        assertFalse(offset.isSourceEventTime());
    }

    @Test
    public void primaryKeyTableUsesPrimaryKey() {
        Table table = table(new Field("id", "BIGINT", Types.BIGINT, true));
        RowIdentityResolver.RowIdentity identity = RowIdentityResolver.resolve(table);
        assertEquals(RowIdentityResolver.Strategy.PRIMARY_KEY, identity.getStrategy());
        assertTrue(identity.isUsableAsWhereKey());
        assertEquals(Collections.singletonList("id"), identity.getKeyFields());
        assertEquals(Collections.singletonList("id"),
                PrimaryKeyUtil.findTablePrimaryKeys(table));
    }

    @Test
    public void noPkTableFallsBackToUniqueIndexOnlyWhenEnabled() {
        Table table = new Table();
        table.setName("t_order");
        Properties ext = new Properties();
        ext.setProperty("uniqueColumns", "order_no, biz_id");
        table.setExtInfo(ext);
        table.setColumn(Arrays.asList(
                new Field("order_no", "VARCHAR", Types.VARCHAR, false),
                new Field("biz_id", "VARCHAR", Types.VARCHAR, false),
                new Field("amount", "DECIMAL", Types.DECIMAL, false)));

        RowIdentityResolver.installFallbackProvider(() -> true);
        assertEquals(RowIdentityResolver.Strategy.UNIQUE_KEY,
                RowIdentityResolver.resolve(table).getStrategy());
        assertEquals(Arrays.asList("order_no", "biz_id"),
                PrimaryKeyUtil.findTablePrimaryKeys(table));

        RowIdentityResolver.installFallbackProvider(() -> false);
        try {
            PrimaryKeyUtil.findTablePrimaryKeys(table);
            fail("关闭退化支持后应恢复旧行为并抛出缺主键异常");
        } catch (SdkException expected) {
            assertTrue(expected.getMessage().contains("缺少主键"));
        }
    }

    @Test
    public void fullRowHashIsNeverUsedAsWhereKey() {
        Table table = table(new Field("c1", "VARCHAR", Types.VARCHAR, false));
        RowIdentityResolver.RowIdentity identity = RowIdentityResolver.resolve(table);
        assertEquals(RowIdentityResolver.Strategy.FULL_ROW_HASH, identity.getStrategy());
        assertFalse("目标表没有哈希列，不能伪装成 WHERE 键", identity.isUsableAsWhereKey());
        assertTrue(identity.getKeyFields().isEmpty());

        Map<String, Object> rowA = new HashMap<>();
        rowA.put("a", 1);
        rowA.put("b", "x");
        Map<String, Object> rowB = new LinkedHashMap<>();
        rowB.put("b", "x");
        rowB.put("a", 1);
        assertNotNull(RowIdentityResolver.fullRowHash(rowA));
        assertEquals("全行哈希必须与 Map 顺序无关",
                RowIdentityResolver.fullRowHash(rowA), RowIdentityResolver.fullRowHash(rowB));
    }

    private static Table table(Field... fields) {
        Table t = new Table();
        t.setName("t_test");
        t.setColumn(Arrays.asList(fields));
        return t;
    }
}
