/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.web.controller.validate.sync;

import org.dbdatasync.biz.TableGroupService;
import org.dbdatasync.biz.ValidateSyncService;
import org.dbdatasync.biz.util.ReconciliationCsvBuilder;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.biz.vo.ValidateSyncTaskVO;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.web.controller.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 订正校验管理
 *
 * @author wuji
 * @version 1.0.0
 * @date 2025-10-18 19:52
 */
@Controller
@RequestMapping("/validate-sync")
public class ValidateSyncController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private ValidateSyncService validateSyncService;

    @Resource
    private TableGroupService tableGroupService;

    /**
     * 任务配置首页
     */
    @RequestMapping("/list")
    public String index(ModelMap model) {
        initEditionInfo(model);
        return "validate-sync/list";
    }

    /**
     * 添加任务页面
     */
    @GetMapping("/pageAdd")
    public String pageAdd(ModelMap model) {
        return "validate-sync/add";
    }

    /**
     * 修改任务页面
     */
    @GetMapping("/page/{page}")
    public String pageEdit(ModelMap model, @PathVariable("page") String page, @RequestParam("id") String id,
                           @RequestParam(value = "detailStatus", required = false) String detailStatus) {
        if (StringUtil.equals("detail", page)) {
            model.put("taskId", id);
            ValidateSyncTaskVO detailTask = validateSyncService.get(id);
            model.put("taskName", detailTask != null ? detailTask.getName() : id);
            model.put("detailStatus", detailStatus == null ? "" : detailStatus.trim());
        } else if (StringUtil.equals("editTableGroup", page)) {
            TableGroup tableGroup = tableGroupService.getTableGroup(id);
            model.put("tableGroup", tableGroup);
            model.put("task", validateSyncService.get(tableGroup.getTaskId()));
        } else {
            model.put("task", validateSyncService.get(id));
        }
        initConditionConfig(model);
        return "validate-sync/" + page;
    }

    /**
     * 添加任务
     */
    @PostMapping("/add")
    @ResponseBody
    public RestResult add(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            return RestResult.restSuccess(validateSyncService.add(params));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 修改任务
     */
    @PostMapping("/edit")
    @ResponseBody
    public RestResult edit(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            return RestResult.restSuccess(validateSyncService.edit(params));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/copy")
    @ResponseBody
    public RestResult add(@RequestParam("id") String id) {
        try {
            return RestResult.restSuccess(validateSyncService.copy(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 删除任务
     */
    @PostMapping("/remove")
    @ResponseBody
    public RestResult remove(@RequestParam(value = "id") String id) {
        try {
            return RestResult.restSuccess(validateSyncService.delete(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 启动任务
     */
    @PostMapping("/start")
    @ResponseBody
    public RestResult start(@RequestParam(value = "id") String id) {
        try {
            return RestResult.restSuccess(validateSyncService.start(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 停止任务
     */
    @PostMapping("/stop")
    @ResponseBody
    public RestResult stop(@RequestParam(value = "id") String id) {
        try {
            return RestResult.restSuccess(validateSyncService.stop(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/search")
    @ResponseBody
    public RestResult list(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            return RestResult.restSuccess(validateSyncService.search(params));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/searchTableGroup")
    @ResponseBody
    public RestResult searchTableGroup(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            return RestResult.restSuccess(validateSyncService.searchTableGroup(params));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/refreshTables")
    @ResponseBody
    public RestResult refreshTables(@RequestParam(value = "id") String id) {
        try {
            return RestResult.restSuccess(validateSyncService.refreshTables(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 分页搜索校验任务表（用于下拉框远程搜索）
     */
    @PostMapping("/searchTables")
    @ResponseBody
    public RestResult searchTables(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            return RestResult.restSuccess(validateSyncService.searchTables(params));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping(value = "/refreshFields")
    @ResponseBody
    public RestResult refreshFields(@RequestParam(value = "id") String id) {
        try {
            return RestResult.restSuccess(validateSyncService.refreshFields(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/addTableGroup")
    @ResponseBody
    public RestResult addTableGroup(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            return RestResult.restSuccess(validateSyncService.addTableGroup(params));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/editTableGroup")
    @ResponseBody
    public RestResult editTableGroup(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            return RestResult.restSuccess(validateSyncService.editTableGroup(params));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/removeTableGroup")
    @ResponseBody
    public RestResult removeTableGroup(
            @RequestParam(value = "taskId", required = false) String taskId,
            @RequestParam(value = "ids") String ids) {
        try {
            return RestResult.restSuccess(validateSyncService.removeTableGroup(taskId, ids));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 查看执行结果
     */
    @PostMapping("/result")
    @ResponseBody
    public RestResult result(@RequestParam(value = "id") String id) {
        try {
            return RestResult.restSuccess(validateSyncService.result(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 分页查询校验结果明细
     */
    @PostMapping("/searchResult")
    @ResponseBody
    public RestResult searchResult(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            return RestResult.restSuccess(validateSyncService.searchResult(params));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 按明细 ID 查询单条校验结果（含完整 CONTENT），供详情弹窗使用。
     */
    @PostMapping("/getResultDetail")
    @ResponseBody
    public RestResult getResultDetail(@RequestParam("taskId") String taskId, @RequestParam("id") String id) {
        try {
            Object detail = validateSyncService.getValidateResultDetail(taskId, id);
            if (detail == null) {
                return RestResult.restFail("记录不存在", 404);
            }
            return RestResult.restSuccess(detail);
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 手动订正单条明细中尚未成功的差异数据。
     */
    @PostMapping("/manualRevise")
    @ResponseBody
    public RestResult manualRevise(@RequestParam("taskId") String taskId, @RequestParam("id") String id) {
        try {
            return RestResult.restSuccess(validateSyncService.manualReviseDetail(taskId, id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 导出对账报告 CSV（只读，不修改任何数据）。
     */
    @GetMapping("/exportReport")
    public void exportReport(@RequestParam("taskId") String taskId,
                             @RequestParam(value = "detailStatus", required = false) String detailStatus,
                             @RequestParam(value = "detailId", required = false) String detailId,
                             HttpServletResponse response) throws IOException {
        try {
            // Wave30：两遍分页扫描取数，不再一次性装载全量明细 List，也不再有 5000 行静默截断
            ReconciliationCsvBuilder.DetailSource source = sink ->
                    validateSyncService.scanResultDetails(taskId, detailStatus, detailId, sink);
            String fileName = "对账报告_" + taskId + "_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv";
            writeCsvResponse(response, fileName, taskId, source);
        } catch (Exception e) {
            logger.error("导出对账报告失败, taskId=" + taskId, e);
            // 响应尚未提交时改写为纯文本，避免浏览器把错误页当成 CSV 下载；已写出则只记日志。
            if (!response.isCommitted()) {
                response.setContentType("text/plain;charset=UTF-8");
                response.setCharacterEncoding("UTF-8");
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                OutputStream os = response.getOutputStream();
                os.write(("导出失败：" + message).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }
    }

    /**
     * 差异条目单条重推。
     */
    @PostMapping("/reviseDiffItem")
    @ResponseBody
    public RestResult reviseDiffItem(@RequestParam("taskId") String taskId,
                                     @RequestParam("id") String detailId,
                                     @RequestParam("itemIndex") int itemIndex) {
        try {
            return RestResult.restSuccess(validateSyncService.reviseDiffItem(taskId, detailId, itemIndex));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 差异条目批量重推（单个明细内多条目勾选）。
     */
    @PostMapping("/reviseDiffItems")
    @ResponseBody
    public RestResult reviseDiffItems(@RequestParam("taskId") String taskId,
                                     @RequestParam("id") String detailId,
                                     @RequestParam("itemIndexes") String itemIndexes) {
        try {
            return RestResult.restSuccess(validateSyncService.reviseDiffItems(taskId, detailId, parseIndexes(itemIndexes)));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 将逗号分隔的差异序号解析为下标列表；空白跳过，非数字抛可读异常。
     */
    private List<Integer> parseIndexes(String raw) {
        List<Integer> indexes = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return indexes;
        }
        String[] tokens = raw.split(",");
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                indexes.add(Integer.parseInt(t));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("差异序号格式非法：" + t);
            }
        }
        return indexes;
    }

    /**
     * 写 CSV 下载流：响应头 + UTF-8 BOM + 流式写出（不再二次拼接/转义，不再整块 getBytes）。
     */
    private void writeCsvResponse(HttpServletResponse response, String fileName, String taskId,
                                  ReconciliationCsvBuilder.DetailSource source) throws IOException {
        response.setContentType("application/octet-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(fileName, "UTF-8"));
        OutputStream os = response.getOutputStream();
        os.write(0xEF);
        os.write(0xBB);
        os.write(0xBF);
        Writer writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
        ReconciliationCsvBuilder.writeTo(writer, taskId, source, System.currentTimeMillis());
        writer.flush();
    }

}