package org.dbdatasync.biz.cutover;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Wave24：切流/回滚真实执行器（无处理器时明确标记 simulated，不冒充执行）。 */
public class Wave24CutoverExecutorTest {

    @Test
    public void withoutHandlerMarksSimulated() {
        DefaultCutoverExecutor executor = new DefaultCutoverExecutor();
        CutoverExecutor.ActionResult result = executor.execute("t1", CutoverPhase.SWITCHING,
                new HashMap<String, String>());
        assertTrue(result.isSimulated());
        assertEquals(2, result.getSteps().size());
        assertEquals(Boolean.FALSE, result.getSteps().get(0).get("executed"));
        assertTrue(executor.executionLog().size() == 1);
    }

    @Test
    public void registeredHandlerReallyExecutesActions() {
        DefaultCutoverExecutor executor = new DefaultCutoverExecutor();
        executor.register((action, taskId, params) -> "handled:" + action + "/" + taskId);
        CutoverExecutor.ActionResult result = executor.execute("t1", CutoverPhase.SWITCHING,
                new HashMap<String, String>());
        assertFalse(result.isSimulated());
        assertTrue(result.isOk());
        assertEquals("handled:STOP_INCREMENT/t1", result.getSteps().get(0).get("detail"));
        assertEquals("handled:FLIP_TRAFFIC_TO_TARGET/t1", result.getSteps().get(1).get("detail"));
    }

    @Test
    public void irreversibleRollbackStepRequiresConfirmation() {
        DefaultCutoverExecutor executor = new DefaultCutoverExecutor();
        executor.register((action, taskId, params) -> "ok:" + action);
        List<RollbackStep> steps = new RollbackPlanner().planFor(CutoverPhase.SWITCHED);
        assertTrue(steps.size() >= 2);

        CutoverExecutor.ActionResult refused = executor.rollback("t1", steps,
                new HashMap<String, String>());
        assertFalse(refused.isOk());

        Map<String, String> confirmed = new HashMap<>();
        confirmed.put("confirm", "true");
        CutoverExecutor.ActionResult applied = executor.rollback("t1", steps, confirmed);
        assertTrue(applied.isOk());
        assertEquals(steps.size(), applied.getSteps().size());
    }

    @Test
    public void managerServiceDelegatesToExecutor() {
        CutoverManagerService service = new CutoverManagerService();
        service.create("t1");
        DefaultCutoverExecutor executor = new DefaultCutoverExecutor();
        executor.register((action, taskId, params) -> "ok");
        service.setExecutor(executor);
        CutoverExecutor.ActionResult result = service.execute("t1", CutoverPhase.SWITCHED,
                new HashMap<String, String>());
        assertFalse(result.isSimulated());
        assertEquals(1, service.executionLog().size());
    }
}
