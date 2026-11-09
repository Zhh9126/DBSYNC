package org.dbdatasync.web.controller.monitor;

import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.sdk.profile.BatchWriteProfiler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * Wave24：大事务批写性能剖析端点。
 *
 * <p>采样点接在 {@code GeneralBufferActuator} 的批量写入前后，输出最近 4096 个批次的
 * p50/p95/p99 耗时、行数分桶、慢批次数与吞吐。用于回答「大事务批写慢在哪里」，
 * 不依赖真实数据库，可在运行时直接查询。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
@Controller
@RequestMapping("/batch-profile")
public class BatchProfileController {

    /** 统计快照 */
    @PostMapping("/report")
    @ResponseBody
    public RestResult report() {
        Map<String, Object> snapshot = BatchWriteProfiler.instance().snapshot();
        return RestResult.restSuccess(snapshot);
    }

    /** 清空样本与累计量（新一轮压测前调用） */
    @PostMapping("/reset")
    @ResponseBody
    public RestResult reset() {
        BatchWriteProfiler.instance().reset();
        return RestResult.restSuccess("reset");
    }
}
