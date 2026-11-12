package org.dbdatasync.biz.slowsql;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Wave24：慢 SQL 任务级上下文解析与按任务聚合。 */
public class Wave24SlowSqlTaskContextTest {

    @Test
    public void extractsTaskIdFromObserverSource() {
        assertEquals("T-100", SlowSqlService.extractTaskId("observer:H2Connection|task=T-100"));
        assertNull(SlowSqlService.extractTaskId("observer:H2Connection"));
        assertNull(SlowSqlService.extractTaskId(null));
        assertNull(SlowSqlService.extractTaskId("observer:H2|task="));
    }

    @Test
    public void collectsAndGroupsByTask() {
        SlowSqlService service = new SlowSqlService();
        service.collect("select 1", "observer:H2|task=T-100", 120L, 1L);
        service.collect("select 2", "observer:H2|task=T-100", 240L, 1L);
        service.collect("select 3", "observer:H2", 500L, 1L);

        assertEquals(3, service.size());
        assertEquals("T-100", service.records().get(0).getTaskId());
        Map<String, Map<String, Object>> report = service.taskReport();
        assertTrue(report.containsKey("T-100"));
        assertTrue(report.containsKey("<unknown>"));
        assertEquals(2, ((Number) report.get("T-100").get("count")).intValue());
        assertEquals(240L, ((Number) report.get("T-100").get("maxMillis")).longValue());
    }
}
