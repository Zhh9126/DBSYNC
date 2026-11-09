package org.dbdatasync.biz.cutover;

import org.dbdatasync.biz.dblink.ConnectionProvider;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wave24：切流 / 回滚执行器默认实现。
 *
 * <p>真实执行路径有两类：</p>
 * <ol>
 *     <li>注册进来的 {@link CutoverExecutor.ActionHandler}（由部署方实现流量切换、
 *         任务启停、反向回灌等外部动作），逐个动作调用并记录结果；</li>
 *     <li>可选的 {@link ConnectionProvider}：当调用方给出 {@code sql} 参数时，
 *         在目标库真实执行该 SQL（用于校验/回灌脚本），执行成功记入台账。</li>
 * </ol>
 *
 * <p><b>诚实边界</b>：没有注册任何处理器、也没有给 SQL 的动作，会明确标记
 * {@code simulated=true} 且 {@code executed=false}，只登记意图，不冒充已执行；
 * 不可逆的回滚步骤（如反向回灌）必须显式 {@code confirm=true} 才执行。
 * 系统配置 {@code enableCutoverAutoExecute} 默认关闭，自动切流需显式开启。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
@Component("defaultCutoverExecutor")
public class DefaultCutoverExecutor implements CutoverExecutor {

    private static final int MAX_LOG = 100;

    private final List<ActionHandler> handlers = new CopyOnWriteArrayList<>();
    private final List<ActionResult> log = new CopyOnWriteArrayList<>();
    private volatile ConnectionProvider connectionProvider;

    public void setConnectionProvider(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void register(ActionHandler handler) {
        if (handler != null && !handlers.contains(handler)) {
            handlers.add(handler);
        }
    }

    @Override
    public ActionResult execute(String taskId, CutoverPhase to, Map<String, String> params) {
        ActionResult result = new ActionResult();
        result.setTaskId(taskId);
        result.setPhase(to == null ? "" : to.name());
        Map<String, String> safeParams = params == null ? Collections.<String, String>emptyMap() : params;
        List<String> actions = actionsFor(to);
        for (String action : actions) {
            String detail = dispatch(action, taskId, safeParams, result);
            result.addStep(action, detail != null, detail == null ? "未接入真实处理器，仅登记意图" : detail);
        }
        // 调用方提供的目标端 SQL：真实执行（校验 / 回灌脚本）
        String sql = safeParams.get("sql");
        if (sql != null && !sql.trim().isEmpty()) {
            try {
                String detail = executeSql(sql.trim());
                result.addStep("EXECUTE_SQL", true, detail);
            } catch (Exception e) {
                result.setOk(false);
                result.addStep("EXECUTE_SQL", false, String.valueOf(e.getMessage()));
            }
        }
        if (result.isSimulated()) {
            result.setMessage("部分动作未接入真实处理器，已明确标记 simulated，不算执行完成");
        } else {
            result.setMessage("已执行 " + actions.size() + " 个相位动作");
        }
        appendLog(result);
        return result;
    }

    @Override
    public ActionResult rollback(String taskId, List<RollbackStep> steps, Map<String, String> params) {
        ActionResult result = new ActionResult();
        result.setTaskId(taskId);
        result.setPhase("ROLLBACK");
        Map<String, String> safeParams = params == null ? Collections.<String, String>emptyMap() : params;
        boolean confirmed = Boolean.parseBoolean(safeParams.get("confirm"));
        if (steps == null || steps.isEmpty()) {
            result.setMessage("当前相位无可执行的回滚预案");
            appendLog(result);
            return result;
        }
        for (RollbackStep step : steps) {
            if (!step.isReversible() && !confirmed) {
                result.setOk(false);
                result.addStep(step.getName(), false, "不可逆步骤未确认（需 confirm=true）");
                result.setMessage("回滚在不可逆步骤前中止：" + step.getName());
                appendLog(result);
                return result;
            }
            String detail = dispatch(step.getName(), taskId, safeParams, result);
            result.addStep(step.getName(), detail != null,
                    detail == null ? "未接入真实处理器，仅登记预案动作" : detail);
        }
        String sql = safeParams.get("rollbackSql");
        if (sql != null && !sql.trim().isEmpty() && result.isOk()) {
            try {
                result.addStep("EXECUTE_ROLLBACK_SQL", true, executeSql(sql.trim()));
            } catch (Exception e) {
                result.setOk(false);
                result.addStep("EXECUTE_ROLLBACK_SQL", false, String.valueOf(e.getMessage()));
            }
        }
        result.setMessage(result.isSimulated()
                ? "回滚预案动作已登记，但未全部接入真实处理器（simulated）"
                : "回滚步骤已全部执行");
        appendLog(result);
        return result;
    }

    private List<String> actionsFor(CutoverPhase phase) {
        List<String> actions = new ArrayList<>();
        if (phase == null) {
            return actions;
        }
        switch (phase) {
            case SHADOW_WRITING:
                actions.add("ENABLE_SHADOW_WRITE");
                break;
            case SWITCHING:
                actions.add("STOP_INCREMENT");
                actions.add("FLIP_TRAFFIC_TO_TARGET");
                break;
            case SWITCHED:
                actions.add("VERIFY_TARGET");
                actions.add("RESUME_INCREMENT");
                break;
            default:
                actions.add("PHASE_BOOKKEEPING");
                break;
        }
        return actions;
    }

    /** 返回非 null 表示该动作已真实执行；null 表示无处理器（标记 simulated）。 */
    private String dispatch(String action, String taskId, Map<String, String> params, ActionResult result) {
        for (ActionHandler handler : handlers) {
            try {
                String detail = handler.handle(action, taskId, params);
                if (detail != null) {
                    return detail;
                }
            } catch (Exception e) {
                result.setOk(false);
                result.setMessage("动作 " + action + " 执行失败：" + e.getMessage());
                return "FAILED: " + e.getMessage();
            }
        }
        result.setSimulated(true);
        return null;
    }

    private String executeSql(String sql) throws Exception {
        ConnectionProvider provider = connectionProvider;
        if (provider == null) {
            throw new IllegalStateException("未注入 ConnectionProvider，无法真实执行 SQL");
        }
        try (Connection connection = provider.get();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.execute();
            return "SQL 已执行: " + (sql.length() > 120 ? sql.substring(0, 120) + "..." : sql);
        }
    }

    private void appendLog(ActionResult result) {
        log.add(result);
        while (log.size() > MAX_LOG) {
            log.remove(0);
        }
    }

    @Override
    public List<ActionResult> executionLog() {
        return new ArrayList<>(log);
    }
}
