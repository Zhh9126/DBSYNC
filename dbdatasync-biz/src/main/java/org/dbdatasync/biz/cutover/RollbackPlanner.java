package org.dbdatasync.biz.cutover;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 回滚预案生成器。
 * <p>
 * 根据当前切流相位生成对应的回滚预案（步骤列表）与人类可读预案文本：
 * <ul>
 *   <li>SWITCHED（已切换）：4 步 —— 停增量 → 反向回灌 → 切回源端流量 → 校验；</li>
 *   <li>SWITCHING（切流中）：2 步 —— 中止切流 → 回切源端流量；</li>
 *   <li>SHADOW_WRITING（影子写中）：3 步 —— 关闭影子写并清空缓冲 → 回切源端流量 → 校验源端写入。</li>
 * </ul>
 * 其余相位不可回滚，返回空预案并给出原因。本类只生成预案，
 * 不执行任何实际回滚动作，实际执行由调用方通过 SPI 接入。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class RollbackPlanner {

    /** 支持生成回滚预案的相位集合：SWITCHED / SWITCHING / SHADOW_WRITING。 */
    private static final List<CutoverPhase> ROLLBACKABLE_PHASES;

    static {
        List<CutoverPhase> phases = new ArrayList<CutoverPhase>();
        phases.add(CutoverPhase.SWITCHED);
        phases.add(CutoverPhase.SWITCHING);
        phases.add(CutoverPhase.SHADOW_WRITING);
        ROLLBACKABLE_PHASES = Collections.unmodifiableList(phases);
    }

    /**
     * 判断指定相位是否支持回滚。
     *
     * @param phase 目标相位
     * @return 支持回滚返回 true
     */
    public boolean canRollbackFrom(CutoverPhase phase) {
        return phase != null && ROLLBACKABLE_PHASES.contains(phase);
    }

    /**
     * 为指定相位生成回滚预案。
     * <p>
     * 仅 SWITCHED / SWITCHING / SHADOW_WRITING 三个相位可生成预案；
     * 其余相位返回空列表（原因可通过 {@link #unsupportedReason(CutoverPhase)} 获取）。
     * </p>
     *
     * @param phase 当前切流相位
     * @return 回滚步骤列表（不可回滚相位返回空列表）
     */
    public List<RollbackStep> planFor(CutoverPhase phase) {
        List<RollbackStep> steps = new ArrayList<RollbackStep>();
        if (phase == CutoverPhase.SWITCHED) {
            steps.add(new RollbackStep("停止增量同步",
                    "暂停目标端增量同步任务，冻结增量断点，避免回灌期间增量继续写入", true));
            steps.add(new RollbackStep("反向回灌",
                    "将回滚数据窗口内目标端新增/变更数据反向写回源端（不可逆，需人工确认）", false));
            steps.add(new RollbackStep("切回源端流量",
                    "把业务流量从目标端切回源端", true));
            steps.add(new RollbackStep("一致性校验",
                    "校验源端数据完整性与业务写入正常", true));
        } else if (phase == CutoverPhase.SWITCHING) {
            steps.add(new RollbackStep("中止切流",
                    "中止正在进行的切流操作，锁定双端写入，保持数据窗口一致", true));
            steps.add(new RollbackStep("回切源端流量",
                    "把业务流量回切到源端", true));
        } else if (phase == CutoverPhase.SHADOW_WRITING) {
            steps.add(new RollbackStep("关闭影子写",
                    "停止影子写比对并清空影子写缓冲区", true));
            steps.add(new RollbackStep("回切源端流量",
                    "把业务流量全部回切到源端（影子写阶段流量本就在源端，恢复单写即可）", true));
            steps.add(new RollbackStep("校验源端写入",
                    "校验源端写入正常、无残留影子写链路", true));
        }
        return steps;
    }

    /**
     * 输出指定相位的人类可读回滚预案文本。
     * <p>
     * 可回滚相位输出带步骤明细的预案，并附带「回滚窗口 / 数据窗口」操作提示；
     * 不可回滚相位输出原因说明。
     * </p>
     *
     * @param phase 当前切流相位
     * @return 预案文本
     */
    public String planText(CutoverPhase phase) {
        StringBuilder sb = new StringBuilder();
        if (!canRollbackFrom(phase)) {
            sb.append("【回滚预案】当前相位[").append(phase)
              .append("]不可回滚。").append(unsupportedReason(phase));
            return sb.toString();
        }
        List<RollbackStep> steps = planFor(phase);
        sb.append("【回滚预案】当前相位：").append(phase).append("（").append(phase.getDesc()).append("）\n");
        for (int i = 0; i < steps.size(); i++) {
            sb.append("  步骤").append(i + 1).append(". ").append(steps.get(i).toString()).append("\n");
        }
        sb.append("提示：请在预设回滚窗口内执行本预案；反向回灌仅覆盖回滚窗口对应的")
          .append("数据窗口（增量断点之间的数据），执行前务必核对数据窗口边界，")
          .append("不可逆步骤需人工二次确认。");
        return sb.toString();
    }

    /**
     * 获取指定相位不可回滚的原因说明。
     *
     * @param phase 目标相位
     * @return 原因说明文本
     */
    public String unsupportedReason(CutoverPhase phase) {
        if (canRollbackFrom(phase)) {
            return "当前相位支持回滚，无需原因说明。";
        }
        if (phase == null) {
            return "原因：相位为 null，无法生成回滚预案。";
        }
        if (phase == CutoverPhase.INIT || phase == CutoverPhase.FULL_SYNC_DONE
                || phase == CutoverPhase.INCREMENT_SYNCING) {
            return "原因：流量尚未切离源端，无需回滚，可继续推进正向链路。";
        }
        if (phase == CutoverPhase.ROLLING_BACK) {
            return "原因：回滚正在执行中，请等待回滚完成后进入 ROLLED_BACK 相位。";
        }
        if (phase == CutoverPhase.ROLLED_BACK) {
            return "原因：已完成回滚，可从 INCREMENT_SYNCING 重走增量同步。";
        }
        if (phase == CutoverPhase.FAILED) {
            return "原因：割接已失败终止，请先人工排查失败原因后再制定恢复方案。";
        }
        if (phase == CutoverPhase.VERIFYING) {
            return "原因：校验阶段流量仍在源端，尚未开始切流，无流量可回切。";
        }
        return "原因：该相位不属于可回滚相位（仅 SWITCHED/SWITCHING/SHADOW_WRITING 支持回滚）。";
    }
}
