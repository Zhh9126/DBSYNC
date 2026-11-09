package org.dbdatasync.parser.ddl;

import org.dbdatasync.sdk.enums.DDLOperationEnum;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Wave24：DDL schema history + 自动同步 + DDL-DML 顺序一致性守卫。 */
public class Wave24SchemaHistoryTest {

    private String table;

    @After
    public void tearDown() {
        if (table != null) {
            SchemaHistory.current().unblock(table);
        }
    }

    @Test
    public void confirmAppliesDdlThenReplaysSuspendedDmlInOrder() throws Exception {
        table = "wave24_t_" + System.nanoTime();
        SchemaHistory.current().configure(Files.createTempDirectory("wave24-schema-history").toString());
        DdlSyncOrchestrator orchestrator = DdlSyncOrchestrator.current();

        AtomicInteger applied = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        SchemaHistory.Entry entry = orchestrator.record("meta-1", table,
                DDLOperationEnum.CREATE_INDEX, "CREATE INDEX idx ON t (id)",
                "CREATE INDEX idx ON t (id)", System.currentTimeMillis(),
                false, true, (sql, operation) -> {
                    applied.incrementAndGet();
                    return "writerDDL applied";
                });

        assertTrue("未确认的 DDL 必须阻塞同表 DML", orchestrator.isTableBlocked(table));
        assertTrue(orchestrator.suspendDml(table, replayed::incrementAndGet));

        Map<String, Object> result = orchestrator.confirmAndApply(entry.getId(), "tester", null);
        assertEquals(Boolean.TRUE, result.get("ok"));
        assertEquals(1, applied.get());
        assertEquals(1, ((Number) result.get("replayedDml")).intValue());
        assertEquals(1, replayed.get());
        assertFalse(orchestrator.isTableBlocked(table));
        assertEquals(SchemaHistory.STATUS_APPLIED, SchemaHistory.current().find(entry.getId()).getStatus());
    }

    @Test
    public void crossDialectDdlMustNotReplaySourceSql() {
        table = "wave24_x_" + System.nanoTime();
        DdlSyncOrchestrator orchestrator = DdlSyncOrchestrator.current();
        SchemaHistory.Entry entry = orchestrator.record("meta-2", table,
                DDLOperationEnum.CREATE_TABLE, "CREATE TABLE t (id NUMBER)", null,
                0L, true, false, (sql, operation) -> "should-not-run");

        assertEquals(SchemaHistory.STATUS_PENDING, SchemaHistory.current().find(entry.getId()).getStatus());
        assertTrue(orchestrator.isTableBlocked(table));
        Map<String, Object> refused = orchestrator.confirmAndApply(entry.getId(), "tester", null);
        assertEquals(Boolean.FALSE, refused.get("ok"));
        assertEquals(SchemaHistory.STATUS_NEEDS_TARGET_SQL, refused.get("status"));
    }
}
