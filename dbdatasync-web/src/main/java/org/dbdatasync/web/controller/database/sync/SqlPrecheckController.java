/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.web.controller.database.sync;

import org.dbdatasync.biz.sqlscan.SqlPrecheckService;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.biz.vo.SqlPrecheckReportVO;
import org.dbdatasync.web.controller.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 应用 SQL 兼容度预检（Wave10 P1-3，独立接口）。
 *
 * <p>只做参数读取 + 调服务 + {@link RestResult} 包装；<strong>不改动</strong>
 * {@code DatabaseSyncController.add()/edit()/perfAdvice()/precheck()}，
 * 也不注入/调用 {@code PreCheckService} 或 {@code POST /precheck}。
 * 报告仅提示、不阻断任务创建，字段不写入任务。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
@Controller
@RequestMapping("/database-sync")
public class SqlPrecheckController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final SqlPrecheckService sqlPrecheckService = new SqlPrecheckService();

    /**
     * 生成兼容度预检报告（可选输入；不填时返回「未输入，未执行扫描」零副作用结果）。
     *
     * @param request 表单：appSqlText、appSqlDir、sourceDialect、targetDialect
     * @return {@code RestResult<SqlPrecheckReportVO>}
     */
    @PostMapping("/sqlPrecheck")
    @ResponseBody
    public RestResult sqlPrecheck(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            SqlPrecheckReportVO report = sqlPrecheckService.precheck(
                    params.get("appSqlText"),
                    params.get("appSqlDir"),
                    params.get("sourceDialect"),
                    params.get("targetDialect"));
            return RestResult.restSuccess(report);
        } catch (Exception e) {
            logger.error("sql precheck failed", e);
            // 不透传第三方异常 message，统一包装为可读提示
            return RestResult.restFail("SQL 兼容度预检失败，请检查输入后重试");
        }
    }
}
