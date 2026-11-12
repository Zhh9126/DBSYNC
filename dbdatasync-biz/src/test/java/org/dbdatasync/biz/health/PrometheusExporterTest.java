/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.health;

import org.dbdatasync.biz.model.ReplicationHealthReport;
import org.dbdatasync.biz.vo.LinkHealthVO;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Prometheus 文本格式导出的离线单测。
 *
 * @author wuji
 * @version 1.0.0
 */
public class PrometheusExporterTest {

    private static ReplicationHealthReport report() {
        ReplicationHealthReport r = new ReplicationHealthReport();
        r.setGeneratedAt(1700000000000L);
        r.setTotalLinks(1);
        r.setRunningLinks(1);
        r.setHealthyLinks(1);
        r.setHealthScore(100);
        r.setEventsTotal(42);
        r.setRetryRecoveredTotal(3);
        r.setDeadLetterTotal(0);
        r.setMaxStallSeconds(7);
        r.setLagWarnSeconds(60);
        r.setLagCriticalSeconds(300);

        LinkHealthVO link = new LinkHealthVO();
        link.setMetaId("m-1");
        link.setMappingName("订单同步 \"prod\"");
        link.setLevel("HEALTHY");
        link.setLevelLabel("健康");
        link.setScore(100);
        link.setState(1);
        link.setRunning(true);
        link.setStallSeconds(7);
        link.setLastEventTime(1700000000000L);
        link.setTotal(100);
        link.setSuccess(99);
        link.setFail(1);
        link.setEventsTotal(42);
        link.setRetryRecovered(3);
        link.setDeadLetters(0);
        r.setLinks(Collections.singletonList(link));
        return r;
    }

    @Test
    public void rendersHelpTypeAndSamples() {
        String text = PrometheusExporter.render(report());
        assertTrue(text.startsWith("# HELP dbdatasync_up"));
        assertTrue(text.contains("# TYPE dbdatasync_up gauge"));
        assertTrue(text.contains("dbdatasync_up 1"));
        assertTrue(text.contains("dbdatasync_links{state=\"running\"} 1"));
        assertTrue(text.contains("dbdatasync_health_score 100"));
        assertTrue(text.contains("dbdatasync_dead_letters_total 0"));
        assertTrue(text.contains("dbdatasync_link_records{meta_id=\"m-1\",mapping=\"订单同步 \\\"prod\\\"\",result=\"fail\"} 1"));
        assertTrue(text.contains("dbdatasync_jvm_uptime_seconds"));
        assertTrue(text.endsWith("\n"));
    }

    @Test
    public void escapesLabelValues() {
        assertEquals("", PrometheusExporter.escape(null));
        assertEquals("a\\\\b", PrometheusExporter.escape("a\\b"));
        assertEquals("a\\\"b", PrometheusExporter.escape("a\"b"));
        assertEquals("a\\nb", PrometheusExporter.escape("a\nb"));
        assertEquals("a\\rb", PrometheusExporter.escape("a\rb"));
    }

    @Test
    public void nullReportIsSafe() {
        String text = PrometheusExporter.render(null);
        assertTrue(text.contains("unavailable"));
        assertFalse(text.contains("dbdatasync_up 1"));
    }

    @Test
    public void emptyLinksStillExportAggregates() {
        ReplicationHealthReport r = report();
        r.setLinks(Collections.<LinkHealthVO>emptyList());
        String text = PrometheusExporter.render(r);
        assertTrue(text.contains("dbdatasync_events_total 42"));
        assertFalse(text.contains("dbdatasync_link_state{"));
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.Assert.assertEquals(expected, actual);
    }
}
