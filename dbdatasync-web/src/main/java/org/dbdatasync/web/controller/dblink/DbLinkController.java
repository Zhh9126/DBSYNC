package org.dbdatasync.web.controller.dblink;

import org.dbdatasync.biz.ConnectorService;
import org.dbdatasync.biz.dblink.DbLinkInfo;
import org.dbdatasync.biz.dblink.DbLinkQueryManager;
import org.dbdatasync.biz.dblink.DbLinkQueryResult;
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
import java.util.Map;

/**
 * DBLink 查询（Wave20 运行时接线）：方向五「多技术路线补充」的 DBLink 读取通道。
 *
 * <p>按连接器配置 ID 经连接器框架建连：{@code /links} 探测源端 all_db_links 视图；
 * {@code /query} 执行只读（SELECT/WITH）查询，支持 {@code @dblink} 后缀，
 * maxRows 限行。安全边界沿用 biz 层保守口径：非只读语句拒绝、不自动执行任何写操作。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Controller
@RequestMapping("/dblink")
public class DbLinkController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private DbLinkQueryManager dbLinkQueryManager;

    @Resource
    private ConnectorService connectorService;

    /** 功能页面 */
    @GetMapping("")
    public String index(ModelMap model) {
        model.put("connectors", connectorService.getConnectorAll());
        return "dblink/index";
    }

    /** 探测源端可用 DBLink 列表（all_db_links 视图） */
    @PostMapping("/links")
    @ResponseBody
    public RestResult links(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String connectorId = requireConnectorId(params);
            java.util.List<DbLinkInfo> links = dbLinkQueryManager.listLinks(connectorId);
            return RestResult.restSuccess(links);
        } catch (IllegalArgumentException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("dblink list failed", e);
            return RestResult.restFail("DBLink 探测失败：" + e.getMessage());
        }
    }

    /** 执行只读查询（SELECT/WITH，支持 @dblink 后缀） */
    @PostMapping("/query")
    @ResponseBody
    public RestResult query(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String connectorId = requireConnectorId(params);
            String sql = params.get("sql");
            int maxRows = parseInt(params.get("maxRows"), 0);
            DbLinkQueryResult result = dbLinkQueryManager.query(connectorId, sql, maxRows);
            return RestResult.restSuccess(result);
        } catch (IllegalArgumentException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("dblink query failed", e);
            return RestResult.restFail("DBLink 查询失败：" + e.getMessage());
        }
    }

    // ---------- 辅助 ----------

    private static String requireConnectorId(Map<String, String> params) {
        String connectorId = params.get("connectorId");
        if (connectorId == null || connectorId.trim().isEmpty()) {
            throw new IllegalArgumentException("connectorId 不能为空");
        }
        return connectorId.trim();
    }

    private static int parseInt(String raw, int defaultValue) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }
}
