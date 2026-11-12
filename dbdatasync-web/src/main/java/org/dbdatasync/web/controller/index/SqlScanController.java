/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.web.controller.index;

import org.dbdatasync.biz.sqlscan.DdlChangeAssistService;
import org.dbdatasync.biz.sqlscan.DdlChangeDraft;
import org.dbdatasync.biz.sqlscan.DdlDraft;
import org.dbdatasync.biz.sqlscan.DdlDraftGenerator;
import org.dbdatasync.biz.sqlscan.SqlCompatibilityRules;
import org.dbdatasync.biz.sqlscan.SqlCompatibilityScanner;
import org.dbdatasync.biz.sqlscan.SqlScanResult;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.biz.vo.SqlScanRequestVO;
import org.dbdatasync.web.controller.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * SQL 兼容度扫描（迁移前应用适配评估）。
 *
 * <p>对标 NineData SQL 转换 / 阿里 DMS / 达梦迁移助手的「迁移前 SQL 兼容评估」：
 * 静态扫描源库 SQL 中可能无法在目标方言执行的函数/操作符/结构/类型，输出分级问题清单与改写建议，
 * 在连库之前暴露应用适配成本。不做自动转换（避免误导），复杂项给人工重写方向。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
@Controller
@RequestMapping("/sql-scan")
public class SqlScanController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private SqlCompatibilityScanner sqlCompatibilityScanner;

    /**
     * 扫描页面
     */
    @GetMapping("")
    public String index(ModelMap model) {
        model.put("dialects", org.dbdatasync.biz.sqlscan.SqlScanMeta.DIALECTS);
        model.put("covered", org.dbdatasync.biz.sqlscan.SqlScanMeta.COVERED_DIALECTS);
        return "sql-scan/index";
    }

    /**
     * 执行扫描
     */
    @PostMapping("/scan")
    @ResponseBody
    public RestResult scan(@RequestBody SqlScanRequestVO request) {
        try {
            if (request == null || request.getSql() == null || request.getSql().trim().isEmpty()) {
                return RestResult.restFail("SQL 不能为空");
            }
            if (request.getSourceDialect() == null || request.getSourceDialect().trim().isEmpty()
                    || request.getTargetDialect() == null || request.getTargetDialect().trim().isEmpty()) {
                return RestResult.restFail("请选择源方言与目标方言");
            }
            SqlScanResult result = sqlCompatibilityScanner.scan(
                    request.getSql(), request.getSourceDialect().trim(), request.getTargetDialect().trim());
            return RestResult.restSuccess(result);
        } catch (Exception e) {
            logger.error("sql scan failed", e);
            return RestResult.restFail("SQL 扫描失败：" + e.getMessage());
        }
    }

    /**
     * Wave10 P1-1：DDL 建表语句转换草稿（独立接口，既有 {@code POST /sql-scan/scan} 契约零改动）。
     *
     * <p>仅做字符串级类型映射，输出草稿/建议，不连库、不执行 DDL；
     * error 级类型不生成草稿；异常统一包装为可读文案，不透传第三方异常消息。</p>
     *
     * @param request 表单：sql（DDL 文本）、sourceDialect、targetDialect
     * @return {@code RestResult<DdlDraft>}
     */
    @PostMapping("/ddlDraft")
    @ResponseBody
    public RestResult ddlDraft(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String sql = params.get("sql");
            String sourceDialect = params.get("sourceDialect");
            String targetDialect = params.get("targetDialect");
            if (sql == null || sql.trim().isEmpty()) {
                return RestResult.restFail("DDL 不能为空");
            }
            if (sourceDialect == null || sourceDialect.trim().isEmpty()
                    || targetDialect == null || targetDialect.trim().isEmpty()) {
                return RestResult.restFail("请选择源方言与目标方言");
            }
            DdlDraft draft = new DdlDraftGenerator().generate(
                    sql, sourceDialect.trim(), targetDialect.trim());
            return RestResult.restSuccess(draft);
        } catch (Exception e) {
            logger.error("ddl draft failed", e);
            return RestResult.restFail("生成 DDL 转换草稿失败，请检查输入后重试");
        }
    }

    /**
     * Wave13 B 线：DDL 变更辅助（ALTER TABLE 拆解 → 类型映射草稿 + 风险标注，不自动执行）。
     *
     * <p>仅做字符串级拆解与类型映射，输出变更单元、目标端草稿与风险标注；
     * error 级类型不生成草稿；异常统一包装为可读文案，不透传第三方异常消息。</p>
     *
     * @param request 表单：sql（ALTER 语句）、sourceDialect、targetDialect
     * @return {@code RestResult<DdlChangeDraft>}
     */
    @PostMapping("/ddlChangeAssist")
    @ResponseBody
    public RestResult ddlChangeAssist(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String sql = params.get("sql");
            String sourceDialect = params.get("sourceDialect");
            String targetDialect = params.get("targetDialect");
            if (sql == null || sql.trim().isEmpty()) {
                return RestResult.restFail("ALTER 语句不能为空");
            }
            if (sourceDialect == null || sourceDialect.trim().isEmpty()
                    || targetDialect == null || targetDialect.trim().isEmpty()) {
                return RestResult.restFail("请选择源方言与目标方言");
            }
            DdlChangeDraft draft = new DdlChangeAssistService().assist(
                    sql, sourceDialect.trim(), targetDialect.trim());
            return RestResult.restSuccess(draft);
        } catch (Exception e) {
            logger.error("ddl change assist failed", e);
            return RestResult.restFail("生成 DDL 变更辅助草稿失败，请检查输入后重试");
        }
    }
}
