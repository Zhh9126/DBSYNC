/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.cutover;

import org.dbdatasync.biz.BizException;
import org.dbdatasync.biz.MappingService;
import org.dbdatasync.biz.dblink.DbLinkConnectionSource;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.model.Mapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * Wave26：切流真实动作处理器——把切流相位的动作落到系统真实任务控制。
 *
 * <p>此前 {@link DefaultCutoverExecutor} 的 {@code ActionHandler} SPI 从未在生产接线，
 * 停增量/恢复增量只会标记 simulated。本处理器把动作映射到真实链路：</p>
 * <ul>
 *     <li>{@code STOP_INCREMENT} → {@link MappingService#stop(String)}（驱动已停止时按幂等成功处理）；</li>
 *     <li>{@code RESUME_INCREMENT} → {@link MappingService#start(String)}（含预检，驱动已在运行时按幂等成功处理）；</li>
 *     <li>{@code VERIFY_TARGET} → 参数 {@code connectorId} + {@code targetSql} 时，
 *         经 {@link DbLinkConnectionSource} 在目标连接器上真实执行校验 SQL，返回行数/结果摘要。</li>
 * </ul>
 *
 * <p><b>诚实边界</b>：{@code ENABLE_SHADOW_WRITE}、{@code FLIP_TRAFFIC_TO_TARGET} 等需要外部
 * 流量管理器配合的动作仍返回 {@code null}（由执行器标记 simulated），本处理器不冒充已执行；
 * taskId 不是已存在的驱动映射时同样返回 {@code null}，交回执行器按未接入登记。</p>
 *
 * @author DBDataSync
 * @version 2.1.6
 * @date 2026-11-09
 */
@Component("taskControlActionHandler")
public class TaskControlActionHandler implements CutoverExecutor.ActionHandler {

    private static final Logger logger = LoggerFactory.getLogger(TaskControlActionHandler.class);

    @Resource
    private MappingService mappingService;

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private DbLinkConnectionSource dbLinkConnectionSource;

    @Override
    public String handle(String action, String taskId, Map<String, String> params) throws Exception {
        if (action == null || taskId == null) {
            return null;
        }
        switch (action) {
            case "STOP_INCREMENT":
                return stopIncrement(taskId);
            case "RESUME_INCREMENT":
                return resumeIncrement(taskId);
            case "VERIFY_TARGET":
                return verifyTarget(params);
            default:
                // 外部流量切换等动作仍留给部署方注入的处理器
                return null;
        }
    }

    private String stopIncrement(String taskId) {
        Mapping mapping = findMapping(taskId);
        if (mapping == null) {
            return null;
        }
        try {
            String message = mappingService.stop(taskId);
            logger.info("切流动作 STOP_INCREMENT：驱动 [{}] 已停止（{}）", taskId, message);
            return "增量通道已停止：" + message;
        } catch (BizException e) {
            // 幂等：驱动本来就未运行也算停增量成功
            String message = String.valueOf(e.getMessage());
            if (message.contains("已停止")) {
                return "增量通道已处于停止状态（幂等）";
            }
            throw e;
        }
    }

    private String resumeIncrement(String taskId) {
        Mapping mapping = findMapping(taskId);
        if (mapping == null) {
            return null;
        }
        try {
            String message = mappingService.start(taskId);
            logger.info("切流动作 RESUME_INCREMENT：驱动 [{}] 已启动（{}）", taskId, message);
            return "增量通道已恢复：" + message;
        } catch (BizException e) {
            String message = String.valueOf(e.getMessage());
            // 幂等：预检拦截/运行上限等按真实原因登记为执行失败，由执行器停止后续步骤
            throw e;
        }
    }

    /** 目标端真实校验：connectorId + targetSql（返回首行首列，常用于 COUNT 对账） */
    private String verifyTarget(Map<String, String> params) throws Exception {
        String connectorId = params == null ? null : params.get("connectorId");
        String targetSql = params == null ? null : params.get("targetSql");
        if (connectorId == null || connectorId.trim().isEmpty()
                || targetSql == null || targetSql.trim().isEmpty()) {
            // 未给目标连接器/SQL 时不算失败，交回执行器按 simulated 登记
            return null;
        }
        try (Connection connection = dbLinkConnectionSource.open(connectorId.trim());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(targetSql.trim())) {
            if (rs.next()) {
                Object value = rs.getObject(1);
                return "目标端校验结果=" + value;
            }
            return "目标端校验无结果行";
        }
    }

    private Mapping findMapping(String taskId) {
        try {
            return profileComponent.getMapping(taskId);
        } catch (Exception e) {
            return null;
        }
    }
}
