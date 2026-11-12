package org.dbdatasync.biz.health;

import org.dbdatasync.biz.model.ReplicationHealthReport;
import org.dbdatasync.biz.vo.LinkHealthVO;
import org.dbdatasync.sdk.model.ChangedOffset;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Wave23 P0-A 离线测试：真实端到端复制延迟参与健康分级、观察点接收源端时间、
 * Prometheus 导出包含 lag 指标且不把估算值当真实值。
 */
public class Wave23LagAndPrometheusTest {

    @Test
    public void realLagOverridesStallInHealthEvaluation() {
        HealthEvaluator.Input real = new HealthEvaluator.Input(true, true,
                1L, 0d, 0L, 60L, 300L, 5d, 400L, true);
        HealthEvaluator.Output out = HealthEvaluator.evaluate(real);
        assertEquals(HealthLevel.STALE, out.level);
        assertTrue(out.reason.contains("端到端复制延迟"));

        // 同样阈值，但没有源端时间：只看停滞时长，应为健康
        HealthEvaluator.Input estimated = new HealthEvaluator.Input(true, true,
                1L, 0d, 0L, 60L, 300L, 5d, 400L, false);
        HealthEvaluator.Output out2 = HealthEvaluator.evaluate(estimated);
        assertEquals(HealthLevel.HEALTHY, out2.level);
        assertEquals("链路正常", out2.reason);
    }

    @Test
    public void trackerOnlyAcceptsSourceEventTime() {
        ReplicationHealthTracker tracker = new ReplicationHealthTracker();
        tracker.reset();
        long sourceAt = 1700000000000L;
        tracker.onEvent("m1", "UPDATE", sourceAt + 1000L,
                sourceAt, ChangedOffset.EVENT_TIME_SOURCE, "bin.1:10");
        ReplicationHealthTracker.Counters c = tracker.counters("m1");
        assertEquals(1L, c.events);
        assertEquals(sourceAt, c.lastSourceEventTime);
        assertEquals(ChangedOffset.EVENT_TIME_SOURCE, c.lagSource);

        // 估算时间不得覆盖真实源端时间，也不得把 lagSource 改成 source
        tracker.onEvent("m1", "UPDATE", sourceAt + 2000L,
                sourceAt + 2000L, ChangedOffset.EVENT_TIME_ESTIMATED, "bin.1:20");
        ReplicationHealthTracker.Counters c2 = tracker.counters("m1");
        assertEquals(sourceAt, c2.lastSourceEventTime);
        assertEquals(ChangedOffset.EVENT_TIME_ESTIMATED, c2.lagSource);
    }

    @Test
    public void prometheusExportsLagMetricsWithSourceLabel() {
        LinkHealthVO link = new LinkHealthVO();
        link.setMetaId("m1");
        link.setMappingName("订单同步");
        link.setRunning(true);
        link.setLagSeconds(42L);
        link.setLagSource(ChangedOffset.EVENT_TIME_SOURCE);
        link.setLastSourceEventTime(1700000000000L);
        link.setLastEventTime(1700000042000L);

        ReplicationHealthReport report = new ReplicationHealthReport();
        report.setMaxLagSeconds(42L);
        report.setMaxStallSeconds(99L);
        report.setLinks(Collections.singletonList(link));

        String text = PrometheusExporter.render(report);
        assertTrue(text.contains("dbdatasync_max_lag_seconds 42"));
        assertTrue(text.contains("dbdatasync_max_stall_seconds 99"));
        assertTrue(text.contains("dbdatasync_link_lag_seconds{meta_id=\"m1\""));
        assertTrue(text.contains("source=\"source\"} 42"));
        assertTrue(text.contains("dbdatasync_link_last_source_event_timestamp_seconds"));
    }
}
