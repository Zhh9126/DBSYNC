package org.dbdatasync.web.controller.monitor;

import org.dbdatasync.common.ledger.RuntimeLedger;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.web.controller.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wave32：运行态统一账本查询入口。
 *
 * <p>慢 SQL、超大内容外置块、结构变更、切流编排、链路健康此前分别落在文件与进程内存，
 * 用户无从确认「数据是否真的存下来了」。本控制器把这些运行态记录在平台内嵌 H2 元库
 * 的同一张表上暴露出来，可直接核对条数与原始记录。</p>
 */
@Controller
@RequestMapping("/runtime-ledger")
public class RuntimeLedgerController extends BaseController {

    @PostMapping("/summary")
    @ResponseBody
    public RestResult summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", "DBDataSync_runtime_ledger");
        out.put("ready", RuntimeLedger.isReady());
        out.put("slowSql", RuntimeLedger.count(RuntimeLedger.KIND_SLOW_SQL));
        out.put("contentChunk", RuntimeLedger.count(RuntimeLedger.KIND_CONTENT_CHUNK));
        out.put("schemaHistory", RuntimeLedger.count(RuntimeLedger.KIND_SCHEMA_HISTORY));
        out.put("cutover", RuntimeLedger.count(RuntimeLedger.KIND_CUTOVER));
        out.put("linkHealth", RuntimeLedger.count(RuntimeLedger.KIND_LINK_HEALTH));
        out.put("kinds", Arrays.asList(RuntimeLedger.KIND_SLOW_SQL,
                RuntimeLedger.KIND_CONTENT_CHUNK, RuntimeLedger.KIND_SCHEMA_HISTORY,
                RuntimeLedger.KIND_CUTOVER, RuntimeLedger.KIND_LINK_HEALTH));
        return RestResult.restSuccess(out);
    }

    @PostMapping("/list")
    @ResponseBody
    public RestResult list(HttpServletRequest request) {
        String kind = normalizeKind(request.getParameter("kind"));
        String refId = request.getParameter("refId");
        int limit = 100;
        String raw = request.getParameter("limit");
        if (raw != null && raw.trim().length() > 0) {
            try {
                limit = Math.min(1000, Math.max(1, Integer.parseInt(raw.trim())));
            } catch (NumberFormatException ignored) {
                // 保持默认
            }
        }
        return RestResult.restSuccess(RuntimeLedger.list(kind, refId, limit));
    }

    /**
     * /summary 返回驼峰计数字段名，而账本常量是下划线命名。运维常直接复制该键（或写成
     * 连字符形式）来查 /list，未归一化会静默返回空列表，看起来像「数据没存下来」。
     */
    private String normalizeKind(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String k = raw.trim().replace('-', '_').toLowerCase();
        if ("slowsql".equals(k) || "slow_sql".equals(k)) {
            return RuntimeLedger.KIND_SLOW_SQL;
        }
        if ("contentchunk".equals(k) || "content_chunk".equals(k)) {
            return RuntimeLedger.KIND_CONTENT_CHUNK;
        }
        if ("schemahistory".equals(k) || "schema_history".equals(k)) {
            return RuntimeLedger.KIND_SCHEMA_HISTORY;
        }
        if ("cutover".equals(k)) {
            return RuntimeLedger.KIND_CUTOVER;
        }
        if ("linkhealth".equals(k) || "link_health".equals(k)) {
            return RuntimeLedger.KIND_LINK_HEALTH;
        }
        return raw.trim();
    }
}
