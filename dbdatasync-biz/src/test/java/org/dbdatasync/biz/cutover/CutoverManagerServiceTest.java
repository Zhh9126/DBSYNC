package org.dbdatasync.biz.cutover;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link CutoverManagerService} 运行时接线层单测（纯逻辑，无 Spring 上下文）。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class CutoverManagerServiceTest {

    @Test
    public void createTransitionAndFullFlow() {
        CutoverManagerService service = new CutoverManagerService();
        service.create("task-1");
        service.create("task-1"); // 幂等：不重置
        assertEquals(CutoverPhase.INIT, service.current("task-1"));

        service.transition("task-1", CutoverPhase.FULL_SYNC_DONE);
        service.transition("task-1", CutoverPhase.INCREMENT_SYNCING);
        service.transition("task-1", CutoverPhase.SHADOW_WRITING);
        assertEquals(CutoverPhase.SHADOW_WRITING, service.current("task-1"));
        assertEquals(3, service.history("task-1").size());
        assertEquals(1, service.sessionCount());

        // 第二个任务互不干扰
        service.create("task-2");
        assertEquals(2, service.sessionCount());
        assertEquals(CutoverPhase.INIT, service.current("task-2"));
    }

    @Test(expected = IllegalStateException.class)
    public void unknownTaskThrows() {
        new CutoverManagerService().current("no-such-task");
    }

    @Test
    public void shadowWriteAndFlipSafe() {
        CutoverManagerService service = new CutoverManagerService();
        service.create("task-1");
        service.recordShadow("task-1", "t1", "1", "{\"a\":1}", "{\"a\":1}");
        service.recordShadow("task-1", "t1", "2", "{\"a\":2}", "{\"a\":9}");

        int[] stats = service.shadowStats("task-1");
        assertEquals(2, stats[0]);
        assertEquals(1, stats[1]);

        assertFalse(service.flipSafe("task-1", 2));

        service.clearShadow("task-1");
        service.recordShadow("task-1", "t1", "1", "{\"a\":1}", "{\"a\":1}");
        assertTrue(service.flipSafe("task-1", 1));
    }

    @Test
    public void rollbackPlanFollowsCurrentPhase() {
        CutoverManagerService service = new CutoverManagerService();
        service.create("task-1");
        // INIT 不可回滚
        assertFalse(service.canRollback("task-1"));
        assertTrue(service.rollbackPlan("task-1").isEmpty());
        assertTrue(service.rollbackPlanText("task-1").contains("不可"));

        service.transition("task-1", CutoverPhase.FULL_SYNC_DONE);
        service.transition("task-1", CutoverPhase.INCREMENT_SYNCING);
        service.transition("task-1", CutoverPhase.SHADOW_WRITING);
        assertTrue(service.canRollback("task-1"));
        assertEquals(3, service.rollbackPlan("task-1").size());

        // 演练结束清理
        assertTrue(service.remove("task-1"));
        assertFalse(service.remove("task-1"));
        assertEquals(0, service.sessionCount());
    }
}
