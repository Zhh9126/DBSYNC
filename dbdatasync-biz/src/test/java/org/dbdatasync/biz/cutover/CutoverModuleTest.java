package org.dbdatasync.biz.cutover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * cutover 模块单元测试（纯逻辑，无 JDBC / HTTP 依赖）。
 * <p>
 * 覆盖：完整正向链路迁移、非法迁移异常与状态不变、history 记录顺序、
 * 影子写配对/失配计数、flipSafe 阈值判定、三相位回滚预案步数与文本、
 * 不可回滚相位空预案、FAILED 迁移约束。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class CutoverModuleTest {

    // ==================== 状态机：正向链路 ====================

    /**
     * 完整正向链路迁移：INIT 一路推进到 SWITCHED，
     * 再经回滚链 ROLLING_BACK -> ROLLED_BACK 重走增量。
     */
    @Test
    public void testFullForwardChain() {
        CutoverStateMachine sm = new CutoverStateMachine();
        assertEquals(CutoverPhase.INIT, sm.current());

        assertEquals(CutoverPhase.FULL_SYNC_DONE, sm.transition(CutoverPhase.FULL_SYNC_DONE));
        assertEquals(CutoverPhase.INCREMENT_SYNCING, sm.transition(CutoverPhase.INCREMENT_SYNCING));
        assertEquals(CutoverPhase.SHADOW_WRITING, sm.transition(CutoverPhase.SHADOW_WRITING));
        assertEquals(CutoverPhase.VERIFYING, sm.transition(CutoverPhase.VERIFYING));
        assertEquals(CutoverPhase.SWITCHING, sm.transition(CutoverPhase.SWITCHING));
        assertEquals(CutoverPhase.SWITCHED, sm.transition(CutoverPhase.SWITCHED));

        // 回滚链：SWITCHED -> ROLLING_BACK -> ROLLED_BACK -> 重走增量
        assertTrue(sm.canTransition(CutoverPhase.SWITCHED, CutoverPhase.ROLLING_BACK));
        assertEquals(CutoverPhase.ROLLING_BACK, sm.transition(CutoverPhase.ROLLING_BACK));
        assertEquals(CutoverPhase.ROLLED_BACK, sm.transition(CutoverPhase.ROLLED_BACK));
        assertEquals(CutoverPhase.INCREMENT_SYNCING, sm.transition(CutoverPhase.INCREMENT_SYNCING));
        assertEquals(CutoverPhase.INCREMENT_SYNCING, sm.current());
    }

    /**
     * 非法迁移抛 IllegalStateException，消息包含当前相位与目标相位，
     * 且状态机当前相位与历史记录保持不变。
     */
    @Test
    public void testIllegalTransitionThrowsAndStateUnchanged() {
        CutoverStateMachine sm = new CutoverStateMachine();
        sm.transition(CutoverPhase.FULL_SYNC_DONE);
        int historySizeBefore = sm.history().size();

        // INIT 不能直接跳到 SWITCHING；FULL_SYNC_DONE 不能跳到 SWITCHED
        assertFalse(sm.canTransition(CutoverPhase.FULL_SYNC_DONE, CutoverPhase.SWITCHED));
        try {
            sm.transition(CutoverPhase.SWITCHED);
            fail("非法迁移应当抛出 IllegalStateException");
        } catch (IllegalStateException expected) {
            String msg = expected.getMessage();
            assertNotNull(msg);
            assertTrue("异常消息应包含当前相位: " + msg, msg.contains(CutoverPhase.FULL_SYNC_DONE.name()));
            assertTrue("异常消息应包含目标相位: " + msg, msg.contains(CutoverPhase.SWITCHED.name()));
        }

        assertEquals("非法迁移后状态应保持不变", CutoverPhase.FULL_SYNC_DONE, sm.current());
        assertEquals("非法迁移后历史记录不应增加", historySizeBefore, sm.history().size());
    }

    /**
     * history 记录顺序与格式：按迁移顺序追加，格式为 "epochMillis|FROM→TO"。
     */
    @Test
    public void testHistoryOrderAndFormat() {
        CutoverStateMachine sm = new CutoverStateMachine();
        sm.transition(CutoverPhase.FULL_SYNC_DONE);
        sm.transition(CutoverPhase.INCREMENT_SYNCING);
        sm.transition(CutoverPhase.SHADOW_WRITING);

        List<String> history = sm.history();
        assertEquals(3, history.size());

        String first = history.get(0);
        String second = history.get(1);
        String third = history.get(2);

        assertTrue("记录应包含分隔符|: " + first, first.contains(CutoverStateMachine.HISTORY_SEPARATOR));
        assertTrue("记录应包含箭头→: " + first, first.contains(CutoverStateMachine.HISTORY_ARROW));

        // 校验每条记录的时间戳前缀为纯数字（epochMillis）
        String firstPrefix = first.substring(0, first.indexOf(CutoverStateMachine.HISTORY_SEPARATOR));
        Long.parseLong(firstPrefix);

        // 校验 FROM→TO 的顺序与内容
        assertTrue(first.endsWith("INIT" + CutoverStateMachine.HISTORY_ARROW + "FULL_SYNC_DONE"));
        assertTrue(second.endsWith("FULL_SYNC_DONE" + CutoverStateMachine.HISTORY_ARROW + "INCREMENT_SYNCING"));
        assertTrue(third.endsWith("INCREMENT_SYNCING" + CutoverStateMachine.HISTORY_ARROW + "SHADOW_WRITING"));

        // 迁移顺序与 history 顺序一致（保序追加）
        assertTrue(history.indexOf(first) < history.indexOf(second));
        assertTrue(history.indexOf(second) < history.indexOf(third));
    }

    // ==================== 影子写缓冲区 ====================

    /**
     * 影子写配对登记：配对计数、失配计数、同 key 覆盖、clear 清空。
     */
    @Test
    public void testShadowWritePairAndMismatchCounts() {
        ShadowWriteBuffer buffer = new ShadowWriteBuffer();
        assertEquals(0, buffer.pairedCount());
        assertEquals(0, buffer.mismatchCount());

        buffer.recordPair("t_user", "1", "{\"id\":1,\"name\":\"a\"}", "{\"id\":1,\"name\":\"a\"}");
        buffer.recordPair("t_user", "2", "{\"id\":2,\"name\":\"b\"}", "{\"id\":2,\"name\":\"B\"}");
        buffer.recordPair("t_order", "100", "{\"id\":100}", null);
        buffer.recordPair("t_order", "101", null, null);

        assertEquals("应登记 4 对配对数据", 4, buffer.pairedCount());
        assertEquals("应有 2 对失配（内容不等 + 一端为 null）", 2, buffer.mismatchCount());

        // 同 key 重复登记按最新一次覆盖
        buffer.recordPair("t_user", "2", "{\"id\":2,\"name\":\"b\"}", "{\"id\":2,\"name\":\"b\"}");
        assertEquals("同 key 覆盖后总数不变", 4, buffer.pairedCount());
        assertEquals("覆盖修复后失配数减 1", 1, buffer.mismatchCount());

        buffer.clear();
        assertEquals("clear 后配对数归零", 0, buffer.pairedCount());
        assertEquals("clear 后失配数归零", 0, buffer.mismatchCount());
    }

    /**
     * flipSafe 阈值判定：样本不足返回 false；有失配返回 false；
     * 样本量达标且零失配返回 true。
     */
    @Test
    public void testFlipSafeThreshold() {
        ShadowWriteBuffer buffer = new ShadowWriteBuffer();

        // 样本不足：登记 2 条一致样本，阈值 3，应 false
        buffer.recordPair("t1", "1", "{\"v\":1}", "{\"v\":1}");
        buffer.recordPair("t1", "2", "{\"v\":2}", "{\"v\":2}");
        assertFalse("样本不足时不应切流", buffer.flipSafe(3));

        // 有失配：补足样本但存在失配，应 false
        buffer.recordPair("t1", "3", "{\"v\":3}", "{\"v\":3}");
        buffer.recordPair("t1", "4", "{\"v\":4}", "{\"v\":WRONG}");
        assertEquals(4, buffer.pairedCount());
        assertEquals(1, buffer.mismatchCount());
        assertFalse("存在失配时不应切流", buffer.flipSafe(3));

        // 修复失配：样本量达标且零失配，应 true
        buffer.recordPair("t1", "4", "{\"v\":4}", "{\"v\":4}");
        assertEquals(0, buffer.mismatchCount());
        assertTrue("样本达标且零失配应允许切流", buffer.flipSafe(4));
    }

    // ==================== 回滚预案 ====================

    /**
     * 三个可回滚相位的预案步数：SWITCHED=4、SWITCHING=2、SHADOW_WRITING=3，
     * 且 planText 包含关键步骤与「回滚窗口/数据窗口」提示。
     */
    @Test
    public void testRollbackPlansForThreePhases() {
        RollbackPlanner planner = new RollbackPlanner();
        assertTrue(planner.canRollbackFrom(CutoverPhase.SWITCHED));
        assertTrue(planner.canRollbackFrom(CutoverPhase.SWITCHING));
        assertTrue(planner.canRollbackFrom(CutoverPhase.SHADOW_WRITING));

        // SWITCHED：4 步（停增量 -> 反向回灌 -> 切回源端流量 -> 校验）
        List<RollbackStep> switchedPlan = planner.planFor(CutoverPhase.SWITCHED);
        assertEquals(4, switchedPlan.size());
        assertEquals("停止增量同步", switchedPlan.get(0).getName());
        assertEquals("反向回灌", switchedPlan.get(1).getName());
        assertEquals("切回源端流量", switchedPlan.get(2).getName());
        assertEquals("一致性校验", switchedPlan.get(3).getName());
        assertFalse("反向回灌应标注为不可逆", switchedPlan.get(1).isReversible());
        assertTrue("停增量应标注为可逆", switchedPlan.get(0).isReversible());

        // SWITCHING：2 步
        List<RollbackStep> switchingPlan = planner.planFor(CutoverPhase.SWITCHING);
        assertEquals(2, switchingPlan.size());
        assertEquals("中止切流", switchingPlan.get(0).getName());
        assertEquals("回切源端流量", switchingPlan.get(1).getName());

        // SHADOW_WRITING：3 步
        List<RollbackStep> shadowPlan = planner.planFor(CutoverPhase.SHADOW_WRITING);
        assertEquals(3, shadowPlan.size());
        assertEquals("关闭影子写", shadowPlan.get(0).getName());
        assertEquals("回切源端流量", shadowPlan.get(1).getName());
        assertEquals("校验源端写入", shadowPlan.get(2).getName());

        // planText 文本包含步骤与「回滚窗口/数据窗口」提示
        String switchedText = planner.planText(CutoverPhase.SWITCHED);
        assertTrue(switchedText.contains("停止增量同步"));
        assertTrue(switchedText.contains("反向回灌"));
        assertTrue(switchedText.contains("切回源端流量"));
        assertTrue(switchedText.contains("一致性校验"));
        assertTrue("文本应包含回滚窗口提示", switchedText.contains("回滚窗口"));
        assertTrue("文本应包含数据窗口提示", switchedText.contains("数据窗口"));
        assertTrue("不可逆步骤应在文本中标注", switchedText.contains("不可逆"));

        String switchingText = planner.planText(CutoverPhase.SWITCHING);
        assertTrue(switchingText.contains("中止切流"));
        assertTrue(switchingText.contains("回滚窗口"));

        String shadowText = planner.planText(CutoverPhase.SHADOW_WRITING);
        assertTrue(shadowText.contains("关闭影子写"));
        assertTrue(shadowText.contains("回滚窗口"));
    }

    /**
     * 不可回滚相位：planFor 返回空列表、canRollbackFrom 为 false、
     * planText 输出原因说明。
     */
    @Test
    public void testNonRollbackablePhasesReturnEmpty() {
        RollbackPlanner planner = new RollbackPlanner();
        CutoverPhase[] phases = {
                CutoverPhase.INIT, CutoverPhase.FULL_SYNC_DONE, CutoverPhase.INCREMENT_SYNCING,
                CutoverPhase.VERIFYING, CutoverPhase.ROLLING_BACK, CutoverPhase.ROLLED_BACK,
                CutoverPhase.FAILED};

        for (CutoverPhase phase : phases) {
            assertFalse("相位 " + phase + " 不应支持回滚", planner.canRollbackFrom(phase));
            assertTrue("相位 " + phase + " 预案应为空", planner.planFor(phase).isEmpty());
            String text = planner.planText(phase);
            assertNotNull(text);
            assertTrue("相位 " + phase + " 文本应说明不可回滚", text.contains("不可回滚"));
            assertTrue("相位 " + phase + " 文本应包含原因", text.contains("原因"));
        }
    }

    // ==================== 失败链约束 ====================

    /**
     * 仅 SHADOW_WRITING / VERIFYING / SWITCHING 三个相位可进入 FAILED，
     * 其他相位进入 FAILED 均为非法迁移。
     */
    @Test
    public void testFailedPhaseTransitionRules() {
        // SHADOW_WRITING -> FAILED
        CutoverStateMachine sm1 = new CutoverStateMachine();
        sm1.transition(CutoverPhase.FULL_SYNC_DONE);
        sm1.transition(CutoverPhase.INCREMENT_SYNCING);
        sm1.transition(CutoverPhase.SHADOW_WRITING);
        assertTrue(sm1.canTransition(CutoverPhase.SHADOW_WRITING, CutoverPhase.FAILED));
        assertEquals(CutoverPhase.FAILED, sm1.transition(CutoverPhase.FAILED));
        assertEquals("FAILED 迁移应记入历史", 4, sm1.history().size());
        assertFalse("FAILED 为终态，不能再迁移", sm1.canTransition(CutoverPhase.FAILED, CutoverPhase.INCREMENT_SYNCING));

        // VERIFYING -> FAILED
        CutoverStateMachine sm2 = buildAt(CutoverPhase.VERIFYING);
        assertTrue(sm2.canTransition(CutoverPhase.VERIFYING, CutoverPhase.FAILED));

        // SWITCHING -> FAILED
        CutoverStateMachine sm3 = buildAt(CutoverPhase.SWITCHING);
        assertTrue(sm3.canTransition(CutoverPhase.SWITCHING, CutoverPhase.FAILED));

        // INIT / SWITCHED 不能进入 FAILED
        CutoverStateMachine sm4 = new CutoverStateMachine();
        assertFalse(sm4.canTransition(CutoverPhase.INIT, CutoverPhase.FAILED));
        CutoverStateMachine sm5 = buildAt(CutoverPhase.SWITCHED);
        assertFalse(sm5.canTransition(CutoverPhase.SWITCHED, CutoverPhase.FAILED));
        try {
            sm5.transition(CutoverPhase.FAILED);
            fail("SWITCHED -> FAILED 应当抛出 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 预期行为
        }
    }

    /**
     * 构造一个已推进到指定相位的独立状态机，用于隔离测试各相位约束。
     *
     * @param target 目标相位（须位于正向主链上）
     * @return 状态机实例
     */
    private static CutoverStateMachine buildAt(CutoverPhase target) {
        CutoverStateMachine sm = new CutoverStateMachine();
        CutoverPhase[] chain = {
                CutoverPhase.FULL_SYNC_DONE, CutoverPhase.INCREMENT_SYNCING, CutoverPhase.SHADOW_WRITING,
                CutoverPhase.VERIFYING, CutoverPhase.SWITCHING, CutoverPhase.SWITCHED};
        for (CutoverPhase step : chain) {
            if (sm.current() == target) {
                break;
            }
            sm.transition(step);
        }
        assertEquals(target, sm.current());
        return sm;
    }
}
