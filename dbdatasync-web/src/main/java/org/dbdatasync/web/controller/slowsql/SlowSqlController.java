package org.dbdatasync.web.controller.slowsql;

import org.dbdatasync.biz.slowsql.SlowSqlReport;
import org.dbdatasync.biz.slowsql.SlowSqlService;
import org.dbdatasync.biz.slowsql.SlowSqlThreshold;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.web.controller.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 慢 SQL 采集（Wave17 运行时接线）。
 *
 * <p>采集口径：引擎/驱动执行耗时回调 {@code POST /slow-sql/collect} 入环形缓冲；
 * {@code POST /slow-sql/report} 输出 avg/max/p95/topN 统计；{@code POST /slow-sql/config}
 * 运行时调整阈值/容量/采样率。纯内存实现，不连库。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Controller
@RequestMapping("/slow-sql")
public class SlowSqlController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private SlowSqlService slowSqlService;

    /** 采集一条 SQL 执行记录（引擎/驱动挂钩调用点） */
    @PostMapping("/collect")
    @ResponseBody
    public RestResult collect(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String sql = params.get("sql");
            if (sql == null || sql.trim().isEmpty()) {
                return RestResult.restFail("sql 不能为空");
            }
            long executeMillis = parseLong(params.get("executeMillis"), 0L);
            long rows = parseLong(params.get("rows"), 0L);
            slowSqlService.collect(sql, params.get("source"), executeMillis, rows);
            return RestResult.restSuccess("collected");
        } catch (Exception e) {
            logger.error("slow sql collect failed", e);
            return RestResult.restFail("慢 SQL 采集失败：" + e.getMessage());
        }
    }

    /** 统计报告（thresholdMillis/topN 可选，缺省用当前口径） */
    @PostMapping("/report")
    @ResponseBody
    public RestResult report(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            long thresholdMillis = parseLong(params.get("thresholdMillis"), 0L);
            int topN = (int) parseLong(params.get("topN"), 10L);
            SlowSqlReport report = slowSqlService.report(thresholdMillis, topN);
            return RestResult.restSuccess(report);
        } catch (Exception e) {
            logger.error("slow sql report failed", e);
            return RestResult.restFail("慢 SQL 报告生成失败：" + e.getMessage());
        }
    }

    /** 当前快照（按存入顺序的只读副本） */
    @PostMapping("/records")
    @ResponseBody
    public RestResult records() {
        return RestResult.restSuccess(slowSqlService.records());
    }

    /** 运行时调整采集口径（thresholdMillis/maxRecords/sampleRate，可只传部分） */
    @PostMapping("/config")
    @ResponseBody
    public RestResult config(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            Long thresholdMillis = params.get("thresholdMillis") == null
                    ? null : parseLong(params.get("thresholdMillis"), SlowSqlThreshold.DEFAULT_THRESHOLD_MILLIS);
            Integer maxRecords = params.get("maxRecords") == null
                    ? null : (int) parseLong(params.get("maxRecords"), SlowSqlThreshold.DEFAULT_MAX_RECORDS);
            Double sampleRate = params.get("sampleRate") == null
                    ? null : Double.valueOf(params.get("sampleRate"));
            slowSqlService.configure(thresholdMillis, maxRecords, sampleRate);
            return RestResult.restSuccess(slowSqlService.getThreshold());
        } catch (NumberFormatException e) {
            return RestResult.restFail("数值参数格式非法");
        } catch (Exception e) {
            logger.error("slow sql config failed", e);
            return RestResult.restFail("慢 SQL 配置失败：" + e.getMessage());
        }
    }

    private static long parseLong(String raw, long defaultValue) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        return Long.parseLong(raw.trim());
    }
}
