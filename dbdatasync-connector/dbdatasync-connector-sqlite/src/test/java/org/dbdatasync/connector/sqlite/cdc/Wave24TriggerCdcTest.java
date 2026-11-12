package org.dbdatasync.connector.sqlite.cdc;

import org.dbdatasync.sdk.constant.ConnectorConstant;
import org.dbdatasync.sdk.listener.event.RowChangedEvent;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.Table;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave24：Trigger CDC 真库（SQLite 文件库）往返测试。
 * 不是 mock：真实建表、真实建触发器、真实 DML、真实轮询还原行事件与位点。
 */
public class Wave24TriggerCdcTest {

    @Test
    public void realSqliteTriggerRoundTrip() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dir = Files.createTempDirectory("wave24-trigger-cdc");
        Path db = dir.resolve("source.db");
        String url = "jdbc:sqlite:" + db;
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t1 (id INTEGER, name TEXT)");
        }

        Table table = new Table();
        table.setName("t1");
        table.setColumn(Arrays.<Field>asList(
                new Field("id", "INTEGER", Types.INTEGER),
                new Field("name", "TEXT", Types.VARCHAR)));

        TriggerLogListener listener = new TriggerLogListener();
        listener.setConnectionSupplier(() -> DriverManager.getConnection(url));
        listener.setSourceTable(Collections.singletonList(table));
        listener.setFilterTable(new HashSet<>(Collections.singletonList("t1")));
        List<RowChangedEvent> events = new ArrayList<>();
        listener.setEventSink(events::add);

        listener.installSchema();
        listener.installTriggers();

        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO t1 VALUES (1, 'alice')");
            st.executeUpdate("UPDATE t1 SET name = 'bob' WHERE id = 1");
            st.executeUpdate("DELETE FROM t1 WHERE id = 1");
        }

        assertEquals(3, listener.poll());
        assertEquals(3, events.size());
        assertEquals(ConnectorConstant.OPERTION_INSERT, events.get(0).getEvent());
        assertEquals(ConnectorConstant.OPERTION_UPDATE, events.get(1).getEvent());
        assertEquals(ConnectorConstant.OPERTION_DELETE, events.get(2).getEvent());
        assertEquals("t1", events.get(0).getSourceTableName());
        assertTrue("CDC 事件必须带源端时间", events.get(0).getChangedOffset().isSourceEventTime());
        assertEquals("1", String.valueOf(events.get(0).getChangedRow().get(0)));
        assertEquals("bob", events.get(1).getChangedRow().get(1));
        // 位点单调递增，且消费后日志被清理
        assertTrue(((Number) events.get(2).getChangedOffset().getPosition()).longValue()
                > ((Number) events.get(0).getChangedOffset().getPosition()).longValue());
        assertEquals(0, listener.poll());
        listener.close();
    }

    @Test
    public void payloadParserHandlesQuotedValuesAndNulls() {
        assertEquals(2, TriggerLogListener.parsePayload("id='1';name='a;b'").size());
        assertEquals("a", TriggerLogListener.unquote("'a'"));
        assertEquals("it's", TriggerLogListener.unquote("'it''s'"));
        assertNull(TriggerLogListener.unquote("NULL"));
        assertTrue(TriggerLogListener.parsePayload(null).isEmpty());
    }
}
