/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.web.controller.database.sync;

import org.dbdatasync.biz.ValidateSyncService;
import org.dbdatasync.biz.DatabaseSyncService;
import org.dbdatasync.biz.vo.DatabaseSyncTaskVO;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.common.enums.CommonTaskStatusEnum;
import org.dbdatasync.web.controller.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 整库迁移
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-05-22 00:00
 */
@Controller
@RequestMapping("/database-sync")
public class DatabaseSyncController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private DatabaseSyncService databaseSyncService;

    @Resource
    private ValidateSyncService validateSyncService;

    @Resource
    private org.dbdatasync.biz.precheck.PreCheckService preCheckService;

    @RequestMapping("/list")
    public String list(ModelMap model) {
        initEditionInfo(model);
        return "database-sync/list";
    }

    /**
     * 添加任务页面
     */
    @GetMapping("/pageAdd")
    public String pageAdd(ModelMap model) {
        model.put("readOnly", false);
        return "database-sync/add";
    }

    /**
     * 任务子页面（与订正校验一致：/page/{page}?id=xxx）
     */
    @GetMapping("/page/{page}")
    public String page(ModelMap model, @PathVariable("page") String page, @RequestParam("id") String id,
                      @RequestParam(value = "detailStatus", required = false) String detailStatus,
                      @RequestParam(value = "detailType", required = false) String detailType) {
        if ("detail".equals(page)) {
            Assert.hasText(id, "任务 ID 不能为空");
            DatabaseSyncTaskVO task = databaseSyncService.get(id);
            model.put("taskId", id);
            model.put("taskName", task != null ? task.getName() : id);
            model.put("detailStatus", detailStatus == null ? "" : detailStatus.trim());
            model.put("detailType", detailType == null ? "" : detailType.trim());
        } else if ("edit".equals(page)) {
            Assert.hasText(id, "任务 ID 不能为空");
            DatabaseSyncTaskVO task = databaseSyncService.get(id);
            model.put("task", task);
            Integer metaState = task.getMetaState();
            model.put("readOnly", metaState != null && CommonTaskStatusEnum.isRunning(metaState));
        }
        return "database-sync/" + page;
    }

    @PostMapping("/search")
    @ResponseBody
    public RestResult search(HttpServletRequest request) {
        try {
            return RestResult.restSuccess(databaseSyncService.search(getParams(request)));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/add")
    @ResponseBody
    public RestResult add(HttpServletRequest request) {
        try {
            return RestResult.restSuccess(databaseSyncService.add(getParams(request)));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/edit")
    @ResponseBody
    public RestResult edit(HttpServletRequest request) {
        try {
            return RestResult.restSuccess(databaseSyncService.edit(getParams(request)));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/remove")
    @ResponseBody
    public RestResult remove(@RequestParam("id") String id) {
        try {
            return RestResult.restSuccess(databaseSyncService.delete(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/start")
    @ResponseBody
    public RestResult start(@RequestParam("id") String id) {
        try {
            return RestResult.restSuccess(databaseSyncService.start(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/stop")
    @ResponseBody
    public RestResult stop(@RequestParam("id") String id) {
        try {
            return RestResult.restSuccess(databaseSyncService.stop(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/precheck")
    @ResponseBody
    public RestResult precheck(@RequestParam("id") String id) {
        try {
            return RestResult.restSuccess(preCheckService.preCheckDatabaseSync(id));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 一键接续增量：为整库迁移任务创建增量（CDC/定时）驱动（autoStart=true 时直接启动）。
     */
    @PostMapping("/upgrade")
    @ResponseBody
    public RestResult upgrade(@RequestParam("id") String id,
                              @RequestParam(value = "autoStart", required = false, defaultValue = "true") boolean autoStart) {
        try {
            return RestResult.restSuccess(databaseSyncService.upgradeToIncrement(id, autoStart));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 实例级一键映射：枚举源连接器全部业务库，生成「源库 -> 目标库」映射对（同名库）。
     */
    @PostMapping("/expandDatabases")
    @ResponseBody
    public RestResult expandDatabases(HttpServletRequest request) {
        try {
            return RestResult.restSuccess(databaseSyncService.expandDatabases(getParams(request)));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 从整库迁移任务一键创建数据对比任务。
     * <p>按迁移任务的每个同步映射(mappingId)关联生成订正校验任务
     * (自动复制源/目标连接器、库表与全部表组映射)，用于迁移/同步完成后的数据一致性对比。</p>
     *
     * @param id   整库迁移任务 ID
     * @param name 对比任务名称前缀(可选, 默认“任务名-数据对比”)
     * @return 创建的对比任务 ID 列表(与映射一一对应)
     */
    @PostMapping("/createValidateSync")
    @ResponseBody
    public RestResult createValidateSync(@RequestParam("id") String id,
                                         @RequestParam(value = "name", required = false) String name) {
        try {
            DatabaseSyncTaskVO task = databaseSyncService.get(id);
            Assert.notNull(task, "整库迁移任务不存在");
            List<String> mappingIds = task.getMappingIds();
            Assert.notEmpty(mappingIds, "该任务尚未生成同步映射, 无法发起数据对比");
            String prefix = org.dbdatasync.common.util.StringUtil.isNotBlank(name) ? name
                    : task.getName() + "-数据对比";
            List<String> created = new ArrayList<>();
            for (String mappingId : mappingIds) {
                Map<String, String> params = new HashMap<>();
                params.put("mappingId", mappingId);
                params.put("name", prefix);
                created.add(validateSyncService.add(params));
            }
            return RestResult.restSuccess(created);
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 预览连接器下表列表（新增页树形选择，滚动分页；offset/limit 由前端传入）
     */
    @PostMapping("/searchResult")
    @ResponseBody
    public RestResult searchResult(HttpServletRequest request) {
        try {
            return RestResult.restSuccess(databaseSyncService.searchResult(getParams(request)));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @PostMapping("/previewTables")
    @ResponseBody
    public RestResult previewTables(HttpServletRequest request) {
        try {
            return RestResult.restSuccess(databaseSyncService.previewTables(getParams(request)));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }
}
