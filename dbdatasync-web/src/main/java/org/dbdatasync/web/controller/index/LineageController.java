/**
 * DataSync Copyright 2026 All Rights Reserved.
 */
package org.dbdatasync.web.controller.index;

import org.dbdatasync.biz.ConnectorService;
import org.dbdatasync.biz.LineageService;
import org.dbdatasync.biz.vo.ConnectorVO;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.parser.model.FieldMapping;
import org.dbdatasync.sdk.model.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据血缘
 *
 * @author AE86
 * @version 1.0.0
 * @date 2026/8/21
 */
@Controller
@RequestMapping("/lineage")
public class LineageController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private LineageService lineageService;

    @Resource
    private ConnectorService connectorService;

    /**
     * 数据血缘页面
     */
    @GetMapping("/list")
    public String list() {
        return "lineage/list";
    }

    /**
     * 查询全部血缘关系（含连接器名称映射，供前端渲染图）
     */
    @RequestMapping("/query")
    @ResponseBody
    public RestResult query() {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("edges", lineageService.queryAll());

            List<Map> connectors = new ArrayList<>();
            List<ConnectorVO> connectorAll = connectorService.getConnectorAll();
            if (!CollectionUtils.isEmpty(connectorAll)) {
                for (ConnectorVO vo : connectorAll) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", vo.getId());
                    item.put("name", vo.getName());
                    item.put("type", vo.getType());
                    connectors.add(item);
                }
            }
            result.put("connectors", connectors);
            return RestResult.restSuccess(result);
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    /**
     * 血缘下钻：按 mappingId + 源表 + 目标表，返回对应字段映射明细
     */
    @PostMapping("/fields")
    @ResponseBody
    public RestResult fields(@RequestParam("mappingId") String mappingId,
                             @RequestParam("sourceTable") String sourceTable,
                             @RequestParam("targetTable") String targetTable) {
        try {
            List<FieldMapping> fieldMappings = lineageService.getFieldMappings(mappingId, sourceTable, targetTable);
            List<Map<String, Object>> rows = new ArrayList<>();
            if (!CollectionUtils.isEmpty(fieldMappings)) {
                for (FieldMapping fm : fieldMappings) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("sourceName", fieldName(fm.getSource()));
                    row.put("sourceType", fieldType(fm.getSource()));
                    row.put("sourcePk", fm.getSource() != null && fm.getSource().isPk());
                    row.put("targetName", fieldName(fm.getTarget()));
                    row.put("targetType", fieldType(fm.getTarget()));
                    row.put("targetPk", fm.getTarget() != null && fm.getTarget().isPk());
                    rows.add(row);
                }
            }
            return RestResult.restSuccess(rows);
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    private String fieldName(Field f) {
        if (f == null) {
            return "-";
        }
        return f.getName() == null ? "-" : f.getName();
    }

    private String fieldType(Field f) {
        if (f == null) {
            return "-";
        }
        return f.getTypeName() == null ? "-" : f.getTypeName();
    }

}
