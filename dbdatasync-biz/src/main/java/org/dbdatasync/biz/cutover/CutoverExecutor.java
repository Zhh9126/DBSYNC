package org.dbdatasync.biz.cutover;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave24：切流 / 回滚真实执行器 SPI。
 *
 * <p>Wave17~18 只提供 10 相位状态机、影子写与回滚预案文本，注释里写明
 * 「实际执行由调用方通过 SPI 接入」，但 SPI 一直不存在。本接口把执行动作从状态机里
 * 分离出来：状态机继续负责相位合法性，执行器负责按相位调用真实动作（停增量、
 * 切流量、反向回灌、校验），并逐步记录台账，失败时停止后续步骤。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public interface CutoverExecutor {

    /** 单次执行结果（含逐步台账，不冒充未接线的动作） */
    final class ActionResult {
        private boolean ok = true;
        private boolean simulated = false;
        private String taskId;
        private String phase;
        private String message = "";
        private final List<Map<String, Object>> steps = new ArrayList<>();
        private long timestamp = System.currentTimeMillis();

        public boolean isOk() { return ok; }
        public void setOk(boolean ok) { this.ok = ok; }
        public boolean isSimulated() { return simulated; }
        public void setSimulated(boolean simulated) { this.simulated = simulated; }
        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getPhase() { return phase; }
        public void setPhase(String phase) { this.phase = phase; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getTimestamp() { return timestamp; }
        public List<Map<String, Object>> getSteps() { return Collections.unmodifiableList(steps); }

        public void addStep(String action, boolean executed, String detail) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("action", action);
            step.put("executed", executed);
            step.put("detail", detail);
            steps.add(step);
        }
    }

    /** 外部动作处理器（流量切换 / 任务启停由部署方注入真实实现） */
    interface ActionHandler {
        /**
         * @param action 动作名
         * @param taskId 任务 ID
         * @param params 动作参数
         * @return 执行说明
         */
        String handle(String action, String taskId, Map<String, String> params) throws Exception;
    }

    /** 按目标相位执行切流动作 */
    ActionResult execute(String taskId, CutoverPhase to, Map<String, String> params);

    /** 按回滚预案逐步执行；不可逆步骤必须 confirm=true */
    ActionResult rollback(String taskId, List<RollbackStep> steps, Map<String, String> params);

    /** 注册外部动作处理器 */
    void register(ActionHandler handler);

    /** 执行台账（最近若干次） */
    List<ActionResult> executionLog();
}
