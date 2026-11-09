/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz;

import org.dbdatasync.biz.model.ReplicationHealthReport;
import org.dbdatasync.biz.vo.DeadLetterVO;
import org.dbdatasync.common.model.Paging;

import java.util.Map;

/**
 * 实时链路健康服务。
 *
 * @author wuji
 * @version 1.0.0
 */
public interface ReplicationHealthService {

    /**
     * 汇总所有同步任务的链路健康（含停滞、失败率、死信、自动重试挽回）。
     */
    ReplicationHealthReport queryHealth();

    /**
     * 查询失败明细，并标记哪些已进入死信终态。
     *
     * @param params pageNum、pageSize、status、event
     */
    Paging<DeadLetterVO> queryDeadLetters(Map<String, String> params);

    /**
     * 评估异常链路并按冷却窗口触发告警，返回本次告警条数。
     */
    int evaluateAndAlarm();
}
