package org.dbdatasync.sdk.profile;

import org.dbdatasync.sdk.checkpoint.TargetTxCheckpoint;
import org.dbdatasync.sdk.context.TaskContextHolder;
import org.dbdatasync.sdk.spi.DeploymentService;
import org.dbdatasync.sdk.spi.FencingTokens;
import org.junit.After;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Wave24：批写剖析、任务上下文、fencing 栅栏与事务内位点开关。 */
public class Wave24RuntimeGuardsTest {

    @After
    public void tearDown() {
        TaskContextHolder.clear();
        FencingTokens.install(null);
        FencingTokens.unregister("m1");
        TargetTxCheckpoint.setEnabled(false);
        BatchWriteProfiler.instance().reset();
    }

    @Test
    public void batchWriteProfilerComputesPercentilesAndBuckets() {
        BatchWriteProfiler profiler = BatchWriteProfiler.instance();
        profiler.reset();
        for (int i = 1; i <= 100; i++) {
            profiler.record(i <= 50 ? 10 : 5000, i <= 50 ? 10L : 2000L);
        }
        Map<String, Object> report = profiler.snapshot();
        assertEquals(100, ((Number) report.get("sampleSize")).intValue());
        assertEquals(100L, ((Number) report.get("totalBatches")).longValue());
        assertEquals(50L, ((Number) report.get("slowBatches")).longValue());
        assertEquals(2000L, ((Number) report.get("p95Millis")).longValue());
        assertEquals(2000L, ((Number) report.get("maxMillis")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Long> buckets = (Map<String, Long>) report.get("rowBuckets");
        assertEquals(50L, buckets.get("1-100").longValue());
        assertEquals(50L, buckets.get("1001-10000").longValue());
    }

    @Test
    public void taskContextHolderIsThreadScoped() {
        TaskContextHolder.set("task-42");
        assertEquals("task-42", TaskContextHolder.currentTaskId());
        TaskContextHolder.clear();
        assertEquals(null, TaskContextHolder.currentTaskId());
        TaskContextHolder.set(null);
        assertEquals(null, TaskContextHolder.currentTaskId());
    }

    @Test
    public void fencingTokenRejectsStaleNode() {
        FencingTokens.install(new DeploymentService() {
            @Override
            public boolean isFencingTokenValid(String metaId, long token) {
                return token >= 7L;
            }
        });
        FencingTokens.register("m1", 5L);
        assertFalse(FencingTokens.isValid("m1"));
        FencingTokens.register("m1", 7L);
        assertTrue(FencingTokens.isValid("m1"));
        // 未登记的任务在单机模式下放行
        assertTrue(FencingTokens.isValid("other"));
        FencingTokens.install(null);
        assertTrue(FencingTokens.isValid("m1"));
    }

    @Test
    public void targetTxCheckpointDdlIsDefinedAndDisabledByDefault() {
        assertFalse(TargetTxCheckpoint.isEnabled());
        assertTrue(TargetTxCheckpoint.CREATE_TABLE_SQL.contains("__DBSYNC_CHECKPOINT"));
        TargetTxCheckpoint.setEnabled(true);
        assertTrue(TargetTxCheckpoint.isEnabled());
        TargetTxCheckpoint.setEnabled(false);
    }
}
