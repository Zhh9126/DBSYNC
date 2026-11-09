/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.web.controller.monitor;

import org.dbdatasync.biz.ReplicationHealthService;
import org.dbdatasync.biz.SystemConfigService;
import org.dbdatasync.biz.health.PrometheusExporter;
import org.dbdatasync.biz.replay.DeadLetterReplayOrchestrator;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.parser.model.SystemConfig;
import org.dbdatasync.sdk.spi.DeploymentService;
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
import java.util.Map;

/**
 * 实时链路健康页与 Prometheus 指标端点。
 *
 * <p>安全说明：本控制器路径默认受 Spring Security 保护（需登录会话），
 * 不额外放行。生产环境若需 Prometheus 免认证抓取，建议在网关侧配置
 * 固定 Token / IP 白名单后反向代理到 {@code /link-health/prometheus}，
 * 而不是把端点直接暴露到公网。
 *
 * @author wuji
 * @version 1.0.0
 */
@Controller
@RequestMapping("/link-health")
public class LinkHealthController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private ReplicationHealthService replicationHealthService;

    @Resource
    private SystemConfigService systemConfigService;

    @Resource
    private DeploymentService deploymentService;

    @RequestMapping("")
    public String index(HttpServletRequest request, ModelMap model) {
        initEditionInfo(model);
        return "link-health/list.html";
    }

    @PostMapping("/report")
    @ResponseBody
    public RestResult report() {
        try {
            return RestResult.restSuccess(replicationHealthService.queryHealth());
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/deadLetters")
    @ResponseBody
    public RestResult deadLetters(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            return RestResult.restSuccess(replicationHealthService.queryDeadLetters(params));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @Resource
    private DeadLetterReplayOrchestrator deadLetterReplayOrchestrator;

    /**
     * Wave27：单条死信手动重放。写操作端点：不进 viewer 只读白名单，
     * RBAC 严格模式下 operator / admin 可执行，viewer 被拒绝。
     */
    @PostMapping("/deadLetters/replay")
    @ResponseBody
    public RestResult replayDeadLetter(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String message = deadLetterReplayOrchestrator.replaySingle(
                    params.get("metaId"), params.get("messageId"));
            return RestResult.restSuccess(message);
        } catch (Exception e) {
            logger.warn("死信手动重放请求失败: {}", e.getMessage());
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * Wave23 集群状态：未开启 HA 时返回轻量快照，不触发租约读写。
     */
    @PostMapping("/cluster")
    @ResponseBody
    public RestResult cluster() {
        try {
            SystemConfig config = systemConfigService.getSystemConfig();
            if (config == null || !config.isEnableClusterHA()) {
                Map<String, Object> state = new java.util.LinkedHashMap<String, Object>();
                state.put("role", "STANDALONE");
                state.put("nodeId", deploymentService.nodeId());
                state.put("mode", "ha-disabled");
                state.put("sharedStorageRequired", false);
                return RestResult.restSuccess(state);
            }
            return RestResult.restSuccess(deploymentService.clusterState());
        } catch (Exception e) {
            logger.warn("集群状态读取失败: {}", e.getMessage());
            return RestResult.restFail(e.getMessage());
        }
    }

    @GetMapping(value = "/prometheus", produces = "text/plain;version=0.0.4;charset=UTF-8")
    @ResponseBody
    public String prometheus() {
        try {
            SystemConfig config = systemConfigService.getSystemConfig();
            if (config != null && !config.isEnablePrometheus()) {
                return "# dbdatasync metrics disabled by system config\n";
            }
            return PrometheusExporter.render(replicationHealthService.queryHealth());
        } catch (Exception e) {
            logger.warn("Prometheus 指标导出失败: {}", e.getMessage());
            return "# dbdatasync metrics error: " + e.getMessage() + "\n";
        }
    }
}
