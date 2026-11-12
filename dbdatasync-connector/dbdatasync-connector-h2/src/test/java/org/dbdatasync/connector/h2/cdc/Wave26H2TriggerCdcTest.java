package org.dbdatasync.connector.h2.cdc;

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
import static org.junit.Assert.assertTrue;

/**
 * Wave26：Trigger CDC 通用引擎在 H2 真库上的往返测试。
 * 不是 mock：真实 H2 文件库、真实触发器类挂载、真实 DML、真实轮询还原行事件与位点。
 */
public class Wave26H2TriggerCdcTest {

    @Test
    public void realH2TriggerRoundTrip() throws Exception {
        Class.forName("org.h2.Driver");
        Path dir = Files.createTempDirectory("wave26-h2-trigger-cdc");
        String url = "jdbc:h2:" + dir.resolve("source").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t1 (id INTEGER, name VARCHAR(50))");
        }

        Table table = new Table();
        table.setName("t1");
        table.setColumn(Arrays.<Field>asList(
                new Field("id", "INTEGER", Types.INTEGER),
                new Field("name", "VARCHAR", Types.VARCHAR)));

        H2TriggerLogListener listener = new H2TriggerLogListener();
        listener.setConnectionSupplier(() -> DriverManager.getConnection(url));
        listener.setSourceTable(Collections.singletonList(table));
        listener.setFilterTable(new HashSet<>(Collections.singletonList("t1")));
        List<RowChangedEvent> events = new ArrayList<>();
        listener.setEventSink(events::add);

        listener.installSchema();
        listener.installTriggers();

        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO t1 VALUES (1, 'alice')");
            st.executeUpdate("INSERT INTO t1 VALUES (2, NULL)");
            st.executeUpdate("UPDATE t1 SET name = 'it''s bob' WHERE id = 1");
            st.executeUpdate("DELETE FROM t1 WHERE id = 2");
        }

        assertEquals(4, listener.poll());
        assertEquals(4, events.size());
        assertEquals(ConnectorConstant.OPERTION_INSERT, events.get(0).getEvent());
        assertEquals(ConnectorConstant.OPERTION_INSERT, events.get(1).getEvent());
        assertEquals(ConnectorConstant.OPERTION_UPDATE, events.get(2).getEvent());
        assertEquals(ConnectorConstant.OPERTION_DELETE, events.get(3).getEvent());
        assertEquals("t1", events.get(0).getSourceTableName());
        assertTrue("CDC 事件必须带源端时间", events.get(0).getChangedOffset().isSourceEventTime());
        assertEquals("1", String.valueOf(events.get(0).getChangedRow().get(0)));
        assertEquals("alice", events.get(0).getChangedRow().get(1));
        assertNullValue(events.get(1).getChangedRow().get(1));
        assertEquals("it's bob", events.get(2).getChangedRow().get(1));
        // 位点单调递增，且消费后日志被清理
        assertTrue(((Number) events.get(3).getChangedOffset().getPosition()).longValue()
                > ((Number) events.get(0).getChangedOffset().getPosition()).longValue());
        assertEquals(0, listener.poll());
        listener.close();
    }

    private void assertNullValue(Object value) {
        assertTrue("NULL 值应还原为 null", value == null);
    }
}
