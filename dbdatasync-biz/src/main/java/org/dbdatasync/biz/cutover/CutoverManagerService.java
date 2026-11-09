package org.dbdatasync.biz.cutover;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 切流编排管理服务（Wave17 运行时接线层）。
 *
 * <p>按任务 ID 维护 {@link CutoverStateMachine} 与 {@link ShadowWriteBuffer} 会话，
 * 供 web 层（{@code CutoverController}）驱动「全量→增量→影子写→验证→切流→回滚」全流程。
 * 切流的真实执行（流量切换/双写通道）由调用方在各相位按预案执行，本服务负责状态权威与安全判定。</p>
 *
 * <p>内存会话注册表（进程级）；重启丢失，生产可按需持久化。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Component("cutoverManagerService")
public class CutoverManagerService {

    /** 会话：状态机 + 影子写缓冲 */
    public static final class CutoverSession {
        private final CutoverStateMachine stateMachine = new CutoverStateMachine();
        private final ShadowWriteBuffer shadowWriteBuffer = new ShadowWriteBuffer();

        public CutoverStateMachine getStateMachine() {
            return stateMachine;
        }

        public ShadowWriteBuffer getShadowWriteBuffer() {
            return shadowWriteBuffer;
        }
    }

    private final Map<String, CutoverSession> sessions = new ConcurrentHashMap<String, CutoverSession>();

    /** Wave26：生产接线——Spring 容器内的默认执行器与真实动作处理器（离线 new 时保持为 null） */
    @javax.annotation.Resource
    private DefaultCutoverExecutor defaultCutoverExecutor;

    @javax.annotation.Resource
    private TaskControlActionHandler taskControlActionHandler;

    /** Wave24：真实执行器（默认内置，便于离线单测；部署方可注入外部实现） */
    private volatile CutoverExecutor executor = new DefaultCutoverExecutor();

    /** Wave26：Spring 环境启动后把容器 Bean 接线为真实执行器，并注册真实动作处理器 */
    @javax.annotation.PostConstruct
    void wireRuntimeBeans() {
        if (defaultCutoverExecutor != null) {
            this.executor = defaultCutoverExecutor;
        }
        if (taskControlActionHandler != null) {
            executor.register(taskControlActionHandler);
        }
    }

    public void setExecutor(CutoverExecutor executor) {
        this.executor = executor == null ? new DefaultCutoverExecutor() : executor;
    }

    /** 注入外部动作处理器（流量切换 / 任务启停等真实动作） */
    public void registerActionHandler(CutoverExecutor.ActionHandler handler) {
        executor.register(handler);
    }

    /** 注入目标库连接，供切流/回滚 SQL 真实执行 */
    public void setConnectionProvider(org.dbdatasync.biz.dblink.ConnectionProvider provider) {
        if (executor instanceof DefaultCutoverExecutor) {
            ((DefaultCutoverExecutor) executor).setConnectionProvider(provider);
        }
    }

    /** Wave24：按相位执行真实切流动作（不改变状态机，状态推进由 transition 负责） */
    public CutoverExecutor.ActionResult execute(String taskId, CutoverPhase to,
                                                Map<String, String> params) {
        return executor.execute(taskId, to, params);
    }

    /** Wave24：按当前相位的回滚预案逐步执行真实回滚（不可逆步骤需 confirm=true） */
    public CutoverExecutor.ActionResult rollback(String taskId, Map<String, String> params) {
        return executor.rollback(taskId, rollbackPlan(taskId), params);
    }

    /** Wave24：执行台账 */
    public List<CutoverExecutor.ActionResult> executionLog() {
        return executor.executionLog();
    }

    /** 创建切流会话（已存在则原样返回，不重置） */
    public CutoverSession create(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        CutoverSession created = new CutoverSession();
        CutoverSession existing = sessions.putIfAbsent(taskId.trim(), created);
        return existing != null ? existing : created;
    }

    /** 取会话；不存在抛 IllegalStateException */
    public CutoverSession session(String taskId) {
        CutoverSession s = taskId == null ? null : sessions.get(taskId.trim());
        if (s == null) {
            throw new IllegalStateException("切流会话不存在：" + taskId + "，请先调用 create");
        }
        return s;
    }

    /** 推进相位（非法迁移抛 IllegalStateException，状态不变） */
    public CutoverPhase transition(String taskId, CutoverPhase to) {
        return session(taskId).getStateMachine().transition(to);
    }

    /** 当前相位 */
    public CutoverPhase current(String taskId) {
        return session(taskId).getStateMachine().current();
    }

    /** 相位迁移历史 */
    public List<String> history(String taskId) {
        return session(taskId).getStateMachine().history();
    }

    /** 影子写配对记录 */
    public void recordShadow(String taskId, String table, String pk,
                             String sourceJson, String shadowJson) {
        session(taskId).getShadowWriteBuffer().recordPair(table, pk, sourceJson, shadowJson);
    }

    /** 影子写统计：[配对数, 失配数] */
    public int[] shadowStats(String taskId) {
        ShadowWriteBuffer buffer = session(taskId).getShadowWriteBuffer();
        return new int[]{buffer.pairedCount(), buffer.mismatchCount()};
    }

    /** 切流安全判定（样本数与零失配） */
    public boolean flipSafe(String taskId, int minSamples) {
        return session(taskId).getShadowWriteBuffer().flipSafe(minSamples);
    }

    /** 清空影子写缓冲（重新验证用） */
    public void clearShadow(String taskId) {
        session(taskId).getShadowWriteBuffer().clear();
    }

    /** 当前相位是否可生成回滚预案 */
    public boolean canRollback(String taskId) {
        return new RollbackPlanner().canRollbackFrom(current(taskId));
    }

    /** 当前相位回滚预案步骤 */
    public List<RollbackStep> rollbackPlan(String taskId) {
        return new RollbackPlanner().planFor(current(taskId));
    }

    /** 当前相位回滚预案文本（含不可回滚原因） */
    public String rollbackPlanText(String taskId) {
        return new RollbackPlanner().planText(current(taskId));
    }

    /** 移除会话（演练结束清理） */
    public boolean remove(String taskId) {
        return taskId != null && sessions.remove(taskId.trim()) != null;
    }

    /** 活跃会话数 */
    public int sessionCount() {
        return sessions.size();
    }
}
