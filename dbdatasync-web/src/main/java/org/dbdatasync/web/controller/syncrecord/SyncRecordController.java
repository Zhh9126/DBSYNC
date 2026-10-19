/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.web.controller.syncrecord;

import org.dbdatasync.biz.ConnectorService;
import org.dbdatasync.biz.MappingService;
import org.dbdatasync.biz.TableGroupService;
import org.dbdatasync.biz.vo.MappingVO;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.common.enums.CommonTaskStatusEnum;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.model.Connector;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.sdk.enums.ListenerTypeEnum;
import org.dbdatasync.sdk.enums.ModelEnum;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据同步记录：集中查看所有数据同步任务的执行状态，
 * 并支持导出迁移记录（任务维度）与迁移清单（表映射维度）为 CSV。
 *
 * @author 穿云
 * @version 1.0.0
 * @date 2026-08-27
 */
@Controller
@RequestMapping("/sync-record")
public class SyncRecordController {

    @Resource
    private MappingService mappingService;

    @Resource
    private TableGroupService tableGroupService;

    @Resource
    private ConnectorService connectorService;

    /**
     * 数据同步记录列表页
     */
    @GetMapping("/list")
    public ModelAndView list() {
        ModelAndView mv = new ModelAndView("sync-record/list");
        mv.addObject("connectorTypes", connectorService.getConnectorTypeAll());
        return mv;
    }

    /**
     * 分页查询同步记录（复用映射查询，支持按连接器类型 / 同步方案内存过滤）
     */
    @PostMapping("/search")
    @ResponseBody
    public RestResult search(@RequestParam Map<String, String> params) {
        return RestResult.restSuccess(queryMappings(params, true));
    }

    /**
     * 导出迁移记录（任务维度 CSV）
     */
    @GetMapping("/exportRecords")
    public void exportRecords(@RequestParam Map<String, String> params, HttpServletResponse response) throws IOException {
        Paging<MappingVO> paging = queryMappings(params, false);
        List<MappingVO> list = new ArrayList<>(paging.getData());

        List<MappingVO> records = new ArrayList<>();
        for (MappingVO m : list) {
            records.add(m);
        }

        String[] headers = {"任务名称", "来源连接", "来源类型", "目标连接", "目标类型", "同步方案",
                "执行模式", "增量策略", "总条数", "成功", "失败", "差异", "订正", "状态",
                "开始时间", "结束时间", "耗时(秒)"};
        List<String[]> rows = new ArrayList<>();
        for (MappingVO m : records) {
            Map<String, String> p = safeParams(m);
            String connectorType = "".equals(nvl(connectorType(m.getSourceConnector())))
                    ? "" : nvl(connectorType(m.getSourceConnector()));
            String modelName = modelName(m.getModel());
            String syncMode = nvl(p.get("syncMode"));
            String increment = incrementStrategyText(m);
            String state = stateText(m);
            String begin = beginTimeText(m);
            String end = endTimeText(m);
            String cost = costSeconds(m);
            rows.add(new String[]{
                    nvl(m.getName()),
                    connectorName(m.getSourceConnector()),
                    connectorType,
                    connectorName(m.getTargetConnector()),
                    nvl(connectorType(m.getTargetConnector())),
                    modelName,
                    syncMode,
                    increment,
                    nvl(metaValue(m, "total")),
                    nvl(metaValue(m, "success")),
                    nvl(metaValue(m, "fail")),
                    nvl(metaValue(m, "diff")),
                    nvl(metaValue(m, "fixed")),
                    state,
                    begin,
                    end,
                    cost
            });
        }
        writeCsv(response, "数据同步迁移记录_" + nowCompact() + ".csv", headers, rows);
    }

    /**
     * 导出迁移清单（表映射维度 CSV）
     */
    @GetMapping("/exportManifest")
    public void exportManifest(@RequestParam Map<String, String> params, HttpServletResponse response) throws IOException {
        Paging<MappingVO> paging = queryMappings(params, false);
        List<MappingVO> list = new ArrayList<>(paging.getData());

        String[] headers = {"任务名称", "同步方案", "执行模式", "增量策略",
                "来源连接", "目标连接",
                "源库", "源Schema", "源表", "目标库", "目标Schema", "目标表",
                "字段映射数", "排序号"};
        List<String[]> rows = new ArrayList<>();
        for (MappingVO m : list) {
            Map<String, String> p = safeParams(m);
            // 查询该映射下的全部表映射
            Map<String, String> tgParams = new HashMap<>();
            tgParams.put("mappingId", m.getId());
            tgParams.put("pageNum", "1");
            tgParams.put("pageSize", String.valueOf(100000));
            Paging<TableGroup> tgPaging = tableGroupService.search(tgParams);
            Collection<TableGroup> groups = tgPaging == null ? null : tgPaging.getData();
            if (groups == null || groups.isEmpty()) {
                rows.add(new String[]{
                        nvl(m.getName()),
                        modelName(m.getModel()),
                        nvl(p.get("syncMode")),
                        incrementStrategyText(m),
                        connectorName(m.getSourceConnector()),
                        connectorName(m.getTargetConnector()),
                        "", "", "", "", "", "",
                        "0", ""
                });
                continue;
            }
            for (TableGroup tg : groups) {
                String srcTable = tg.getSourceTable() == null ? "" : nvl(tg.getSourceTable().getName());
                String tgtTable = tg.getTargetTable() == null ? "" : nvl(tg.getTargetTable().getName());
                rows.add(new String[]{
                        nvl(m.getName()),
                        modelName(m.getModel()),
                        nvl(p.get("syncMode")),
                        incrementStrategyText(m),
                        connectorName(m.getSourceConnector()),
                        connectorName(m.getTargetConnector()),
                        nvl(tg.getSourceDatabase()),
                        nvl(tg.getSourceSchema()),
                        srcTable,
                        nvl(tg.getTargetDatabase()),
                        nvl(tg.getTargetSchema()),
                        tgtTable,
                        String.valueOf(tg.getFieldMapping() == null ? 0 : tg.getFieldMapping().size()),
                        String.valueOf(tg.getIndex())
                });
            }
        }
        writeCsv(response, "数据同步迁移清单_" + nowCompact() + ".csv", headers, rows);
    }

    // ------------------------------------------------------------------
    // 查询与过滤
    // ------------------------------------------------------------------

    private Paging<MappingVO> queryMappings(Map<String, String> params, boolean paged) {
        String connectorType = params.get("connectorType");
        String model = params.get("model");
        boolean needFilter = StringUtil.isNotBlank(connectorType) || StringUtil.isNotBlank(model);
        if (!needFilter) {
            return mappingService.search(params);
        }
        int pageNum = toInt(params.get("pageNum"), 1);
        int pageSize = paged ? toInt(params.get("pageSize"), 10) : Integer.MAX_VALUE;
        Map<String, String> all = new HashMap<>(params);
        all.put("pageNum", "1");
        all.put("pageSize", String.valueOf(Integer.MAX_VALUE));
        Paging<MappingVO> paging = mappingService.search(all);
        List<MappingVO> filtered = new ArrayList<>();
        for (MappingVO m : paging.getData()) {
            if (StringUtil.isNotBlank(connectorType) && !connectorType.equals(connectorType(m.getSourceConnector()))) {
                continue;
            }
            if (StringUtil.isNotBlank(model) && !model.equals(m.getModel())) {
                continue;
            }
            filtered.add(m);
        }
        Paging<MappingVO> result = new Paging<>(pageNum, pageSize);
        result.setTotal(filtered.size());
        int from = Math.min((pageNum - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        result.setData(filtered.subList(from, to));
        return result;
    }

    // ------------------------------------------------------------------
    // 字段取值工具
    // ------------------------------------------------------------------

    private Map<String, String> safeParams(MappingVO m) {
        return m.getParams() == null ? new HashMap<>() : m.getParams();
    }

    private String connectorName(Connector c) {
        return c == null ? "" : nvl(c.getName());
    }

    private String connectorType(Connector c) {
        if (c == null || c.getConfig() == null) {
            return "";
        }
        return nvl(c.getConfig().getConnectorType());
    }

    private String modelName(String model) {
        if (StringUtil.isBlank(model)) {
            return "";
        }
        ModelEnum me = ModelEnum.getModelEnum(model);
        return me == null ? model : me.getName();
    }

    private String incrementStrategyText(Mapping m) {
        if (m.getListener() == null || StringUtil.isBlank(m.getListener().getListenerType())) {
            return "无";
        }
        return ListenerTypeEnum.isLog(m.getListener().getListenerType())
                ? "log增量监听" : ListenerTypeEnum.isTiming(m.getListener().getListenerType())
                ? "timing定时拉取" : nvl(m.getListener().getListenerType());
    }

    private String stateText(MappingVO m) {
        if (m.getMeta() == null) {
            return "未运行";
        }
        long state = m.getMeta().getState();
        if (state == 0L) {
            return "未运行";
        }
        for (CommonTaskStatusEnum e : CommonTaskStatusEnum.values()) {
            if (e.getCode() == state) {
                return e.getMessage();
            }
        }
        return String.valueOf(state);
    }

    private String metaValue(MappingVO m, String key) {
        if (m.getMeta() == null) {
            return "0";
        }
        Object v = null;
        switch (key) {
            case "total":
                v = m.getMeta().getTotal();
                break;
            case "success":
                v = m.getMeta().getSuccess();
                break;
            case "fail":
                v = m.getMeta().getFail();
                break;
            case "diff":
                v = m.getMeta().getDiff();
                break;
            case "fixed":
                v = m.getMeta().getFixed();
                break;
            default:
                break;
        }
        return v == null ? "0" : String.valueOf(v);
    }

    private String beginTimeText(MappingVO m) {
        if (m == null || m.getMeta() == null) {
            return "";
        }
        return formatMetaTime(m.getMeta().getBeginTime());
    }

    private String endTimeText(MappingVO m) {
        if (m == null || m.getMeta() == null) {
            return "";
        }
        return formatMetaTime(m.getMeta().getEndTime());
    }

    /**
     * 统一格式化元数据时间：兼容毫秒/秒级时间戳与既有 yyyy-MM-dd HH:mm:ss 字符串。
     */
    private String formatMetaTime(Object t) {
        if (t == null) {
            return "";
        }
        String s = String.valueOf(t);
        if (StringUtil.isBlank(s)) {
            return "";
        }
        if (s.contains("-")) {
            return s;
        }
        try {
            long n = Long.parseLong(s.trim());
            long millis = String.valueOf(n).length() <= 10 ? n * 1000L : n;
            java.time.Instant inst = java.time.Instant.ofEpochMilli(millis);
            return java.time.LocalDateTime.ofInstant(inst, java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private String costSeconds(MappingVO m) {
        if (m == null || m.getMeta() == null) {
            return "";
        }
        try {
            Object b = m.getMeta().getBeginTime();
            Object e = m.getMeta().getEndTime();
            if (b == null || e == null) {
                return "";
            }
            long bm = toMillis(b);
            long em = toMillis(e);
            if (bm > 0 && em >= bm) {
                return String.valueOf((em - bm) / 1000);
            }
        } catch (Exception ignored) {
            // ignore
        }
        return "";
    }

    /**
     * 元数据时间转毫秒：兼容 yyyy-MM-dd HH:mm:ss 字符串、毫秒与秒级时间戳。
     */
    private long toMillis(Object t) {
        String s = String.valueOf(t);
        if (s.contains("-")) {
            return java.time.LocalDateTime.parse(s.replace(' ', 'T'))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        long n = Long.parseLong(s.trim());
        return String.valueOf(n).length() <= 10 ? n * 1000L : n;
    }

    private String nowCompact() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    private static String nvl(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int toInt(String s, int def) {
        if (StringUtil.isBlank(s)) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------
    // CSV 导出
    // ------------------------------------------------------------------

    private void writeCsv(HttpServletResponse response, String fileName, String[] headers, List<String[]> rows) throws IOException {
        response.setContentType("application/octet-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(fileName, "UTF-8"));
        OutputStream os = response.getOutputStream();
        // UTF-8 BOM，保证 Excel 打开中文不乱码
        os.write(0xEF);
        os.write(0xBB);
        os.write(0xBF);
        StringBuilder sb = new StringBuilder();
        appendCsvLine(sb, headers);
        for (String[] row : rows) {
            appendCsvLine(sb, row);
        }
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private void appendCsvLine(StringBuilder sb, String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String cell = cells[i] == null ? "" : cells[i];
            boolean needQuote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
            if (needQuote) {
                sb.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(cell);
            }
        }
        sb.append("\r\n");
    }
}
