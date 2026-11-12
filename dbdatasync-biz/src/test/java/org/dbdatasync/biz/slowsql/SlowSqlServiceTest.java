package org.dbdatasync.biz.slowsql;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link SlowSqlService} 运行时接线层单测（纯逻辑，无 Spring 上下文）。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class SlowSqlServiceTest {

    @Test
    public void collectAndReport() {
        SlowSqlService service = new SlowSqlService();
        service.collect("SELECT 1", "task-a", 100L, 1L);
        service.collect("SELECT 2", "task-a", 2000L, 10L);
        service.collect("SELECT 3", "task-b", 3000L, 5L);

        assertEquals(3, service.size());
        List<SlowSqlRecord> records = service.records();
        assertEquals(3, records.size());
        // 阈值标记由底层存储按当前口径写入
        assertTrue(records.get(1).isThresholdExceeded());
        assertTrue(records.get(2).isThresholdExceeded());

        SlowSqlReport report = service.report(0, 2);
        assertEquals(3, report.getTotalCount());
        assertEquals(2, report.getSlowCount());
        assertEquals(3000L, report.getMaxMillis());
        assertEquals(2, report.getTopSlowest().size());
        assertEquals(3000L, report.getTopSlowest().get(0).getExecuteMillis());
    }

    @Test
    public void configureRebuildsStoreAndValidates() {
        SlowSqlService service = new SlowSqlService();
        service.collect("SELECT 1", "t", 5000L, 1L);
        assertEquals(1, service.size());

        // 重建后历史清空，新口径生效
        service.configure(100L, 10, 1.0);
        assertEquals(0, service.size());
        assertEquals(100L, service.getThreshold().getThresholdMillis());
        assertEquals(10, service.getThreshold().getMaxRecords());

        service.collect("SELECT 2", "t", 50L, 1L);
        // 阈值 100，50 不算慢
        SlowSqlReport report = service.report(0, 5);
        assertEquals(0, report.getSlowCount());
    }

    @Test
    public void invalidConfigureFallsBackToDefaults() {
        SlowSqlService service = new SlowSqlService();
        service.configure(-1L, -5, 9.9);
        assertEquals(SlowSqlThreshold.DEFAULT_THRESHOLD_MILLIS, service.getThreshold().getThresholdMillis());
        assertEquals(SlowSqlThreshold.DEFAULT_MAX_RECORDS, service.getThreshold().getMaxRecords());
        assertEquals(SlowSqlThreshold.DEFAULT_SAMPLE_RATE, service.getThreshold().getSampleRate(), 1e-9);
    }

    @Test
    public void reportWithExplicitThresholdUsesGivenValue() {
        SlowSqlService service = new SlowSqlService();
        service.configure(10000L, 100, 1.0);
        service.collect("SELECT 1", "t", 500L, 1L);
        // 显式阈值 100：500 算慢（不受当前口径 10000 影响）
        SlowSqlReport report = service.report(100L, 5);
        assertEquals(1, report.getSlowCount());
    }
}
