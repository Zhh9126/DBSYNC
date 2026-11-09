/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.health;

import org.dbdatasync.biz.ReplicationHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 链路健康定时评估（每 60 秒，首次延迟 30 秒避开启动预热）。
 *
 * <p>告警是否真正发出由系统配置的开关与冷却窗口决定；本任务只负责触发评估，
 * 任何异常都不得影响同步主链路。
 *
 * @author wuji
 * @version 1.0.0
 */
@Component
public class ReplicationHealthAlarmJob {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private ReplicationHealthService replicationHealthService;

    @Scheduled(initialDelay = 30000, fixedDelay = 60000)
    public void run() {
        try {
            replicationHealthService.evaluateAndAlarm();
        } catch (Exception e) {
            logger.warn("链路健康定时评估异常: {}", e.getMessage());
        }
    }
}
