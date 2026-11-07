package org.dbdatasync.web.controller.cutover;

import org.dbdatasync.biz.cutover.CutoverManagerService;
import org.dbdatasync.biz.cutover.CutoverPhase;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.web.controller.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 切流编排（Wave17 运行时接线）：回滚预案 / 自动切流 / 影子写。
 *
 * <p>状态权威为 {@link CutoverManagerService}（按 taskId 会话）：
 * {@code /create} 建会话 → {@code /transition} 推进相位 → {@code /shadow/record} 影子写配对 →
 * {@code /shadow/flipSafe} 切流安全判定 → {@code /rollbackPlan} 回滚预案。
 * 各相位的真实执行动作（停增量/反向回灌/切流量）由调用方按预案人工或编排执行，本端点不做自动执行。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Controller
@RequestMapping("/cutover")
public class CutoverController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private CutoverManagerService cutoverManagerService;

    /** 功能页面 */
    @GetMapping("")
    public String index(ModelMap model) {
        return "cutover/index";
    }

    /** 创建切流会话（幂等） */
    @PostMapping("/create")
    @ResponseBody
    public RestResult create(HttpServletRequest request) {
        try {
            String taskId = requireTaskId(getParams(request));
            cutoverManagerService.create(taskId);
            return RestResult.restSuccess(phaseView(taskId));
        } catch (IllegalArgumentException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("cutover create failed", e);
            return RestResult.restFail("切流会话创建失败：" + e.getMessage());
        }
    }

    /** 推进相位（to=目标相位枚举名；非法迁移返回失败且状态不变） */
    @PostMapping("/transition")
    @ResponseBody
    public RestResult transition(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String taskId = requireTaskId(params);
            String to = params.get("to");
            if (to == null || to.trim().isEmpty()) {
                return RestResult.restFail("to（目标相位）不能为空");
            }
            CutoverPhase phase = CutoverPhase.valueOf(to.trim().toUpperCase(java.util.Locale.ROOT));
            CutoverPhase current = cutoverManagerService.transition(taskId, phase);
            return RestResult.restSuccess(phaseView(taskId, current));
        } catch (IllegalArgumentException e) {
            return RestResult.restFail("相位非法：" + e.getMessage());
        } catch (IllegalStateException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("cutover transition failed", e);
            return RestResult.restFail("相位推进失败：" + e.getMessage());
        }
    }

    /** 当前状态 + 历史 + 影子写统计 + 回滚预案文本 */
    @PostMapping("/state")
    @ResponseBody
    public RestResult state(HttpServletRequest request) {
        try {
            String taskId = requireTaskId(getParams(request));
            return RestResult.restSuccess(phaseView(taskId));
        } catch (IllegalStateException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("cutover state failed", e);
            return RestResult.restFail("查询切流状态失败：" + e.getMessage());
        }
    }

    /** 影子写配对记录（table/pk/sourceJson/shadowJson） */
    @PostMapping("/shadow/record")
    @ResponseBody
    public RestResult shadowRecord(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String taskId = requireTaskId(params);
            String table = params.get("table");
            String pk = params.get("pk");
            if (table == null || table.trim().isEmpty() || pk == null || pk.trim().isEmpty()) {
                return RestResult.restFail("table 与 pk 不能为空");
            }
            cutoverManagerService.recordShadow(taskId, table.trim(), pk.trim(),
                    params.get("sourceJson"), params.get("shadowJson"));
            return RestResult.restSuccess("recorded");
        } catch (IllegalStateException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("cutover shadow record failed", e);
            return RestResult.restFail("影子写记录失败：" + e.getMessage());
        }
    }

    /** 切流安全判定（minSamples 最小样本数） */
    @PostMapping("/shadow/flipSafe")
    @ResponseBody
    public RestResult flipSafe(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String taskId = requireTaskId(params);
            int minSamples = (int) parseLong(params.get("minSamples"), 1L);
            java.util.Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("flipSafe", cutoverManagerService.flipSafe(taskId, minSamples));
            int[] stats = cutoverManagerService.shadowStats(taskId);
            data.put("pairedCount", stats[0]);
            data.put("mismatchCount", stats[1]);
            return RestResult.restSuccess(data);
        } catch (IllegalStateException e) {
            return RestResult.restFail(e.getMessage());
        } catch (NumberFormatException e) {
            return RestResult.restFail("minSamples 数值非法");
        } catch (Exception e) {
            logger.error("cutover flipSafe failed", e);
            return RestResult.restFail("切流安全判定失败：" + e.getMessage());
        }
    }

    /** 当前相位回滚预案（步骤 + 文本；不可回滚相位返回原因） */
    @PostMapping("/rollbackPlan")
    @ResponseBody
    public RestResult rollbackPlan(HttpServletRequest request) {
        try {
            String taskId = requireTaskId(getParams(request));
            java.util.Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("canRollback", cutoverManagerService.canRollback(taskId));
            data.put("steps", cutoverManagerService.rollbackPlan(taskId));
            data.put("text", cutoverManagerService.rollbackPlanText(taskId));
            return RestResult.restSuccess(data);
        } catch (IllegalStateException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("cutover rollback plan failed", e);
            return RestResult.restFail("回滚预案生成失败：" + e.getMessage());
        }
    }

    /** 移除会话（演练结束清理） */
    @PostMapping("/remove")
    @ResponseBody
    public RestResult remove(HttpServletRequest request) {
        try {
            String taskId = requireTaskId(getParams(request));
            boolean removed = cutoverManagerService.remove(taskId);
            return RestResult.restSuccess(removed);
        } catch (IllegalArgumentException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("cutover remove failed", e);
            return RestResult.restFail("会话移除失败：" + e.getMessage());
        }
    }

    // ---------- 辅助 ----------

    private static String requireTaskId(Map<String, String> params) {
        String taskId = params.get("taskId");
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        return taskId.trim();
    }

    private static long parseLong(String raw, long defaultValue) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        return Long.parseLong(raw.trim());
    }

    private Map<String, Object> phaseView(String taskId) {
        return phaseView(taskId, cutoverManagerService.current(taskId));
    }

    private Map<String, Object> phaseView(String taskId, CutoverPhase current) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("taskId", taskId);
        view.put("phase", current == null ? null : current.name());
        view.put("desc", current == null ? null : current.getDesc());
        view.put("history", cutoverManagerService.history(taskId));
        int[] stats = cutoverManagerService.shadowStats(taskId);
        view.put("pairedCount", stats[0]);
        view.put("mismatchCount", stats[1]);
        return view;
    }
}
