package org.dbdatasync.web.controller.monitor;

import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.parser.ddl.DdlSyncOrchestrator;
import org.dbdatasync.parser.ddl.SchemaHistory;
import org.dbdatasync.web.controller.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Wave24：DDL schema history 与人工确认端点。
 *
 * <p>表级 DDL 由引擎自动登记到 schema history；高风险类型（DROP / TRUNCATE /
 * RENAME / CREATE TABLE）需在此确认后才会在目标端执行，确认时可提供跨方言 targetSql。
 * 确认并应用成功后，同表被挂起的 DML 按原顺序重放。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
@Controller
@RequestMapping("/schema-history")
public class SchemaHistoryController extends BaseController {

    /** 功能页面（Wave25：监控告警 → 结构变更） */
    @RequestMapping("")
    public String index(ModelMap model) {
        return "schema-history/list.html";
    }

    /** 全部历史（按序号） */
    @PostMapping("/list")
    @ResponseBody
    public RestResult list() {
        return RestResult.restSuccess(SchemaHistory.current().list());
    }

    /** 汇总：各状态数量、被阻塞的表、挂起 DML 数 */
    @PostMapping("/summary")
    @ResponseBody
    public RestResult summary() {
        return RestResult.restSuccess(DdlSyncOrchestrator.current().summary());
    }

    /**
     * 人工确认并应用：id 必填，operator / targetSql 可选。
     * 跨源表级 DDL 必须提供 targetSql，否则返回 needs_target_sql，不回放源端 SQL。
     */
    @PostMapping("/confirm")
    @ResponseBody
    public RestResult confirm(HttpServletRequest request) {
        Map<String, String> params = getParams(request);
        String id = params.get("id");
        if (id == null || id.trim().isEmpty()) {
            return RestResult.restFail("id 不能为空");
        }
        Map<String, Object> result = DdlSyncOrchestrator.current().confirmAndApply(
                id.trim(), params.get("operator"), params.get("targetSql"));
        return Boolean.TRUE.equals(result.get("ok"))
                ? RestResult.restSuccess(result) : RestResult.restFail(String.valueOf(result.get("message")));
    }

    /** 运维应急：解除某表的 DML 阻塞（不会执行 DDL，请谨慎使用） */
    @PostMapping("/unblock")
    @ResponseBody
    public RestResult unblock(HttpServletRequest request) {
        Map<String, String> params = getParams(request);
        String table = params.get("table");
        if (table == null || table.trim().isEmpty()) {
            return RestResult.restFail("table 不能为空");
        }
        SchemaHistory.current().unblock(table.trim());
        return RestResult.restSuccess("unblocked");
    }
}
