package org.dbdatasync.web.controller.plugin;

import org.dbdatasync.biz.ConnectorService;
import org.dbdatasync.biz.PluginService;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.common.config.AppConfig;
import org.dbdatasync.common.util.JsonUtil;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/plugin")
public class PluginController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private PluginService pluginService;

    @Resource
    private ConnectorService connectorService;

    @Resource
    private AppConfig appConfig;

    /**
     * 连接器类型 -> 展示名称
     */
    private static final Map<String, String> CONNECTOR_NAMES = new LinkedHashMap<>();

    static {
        CONNECTOR_NAMES.put("MySQL", "MySQL");
        CONNECTOR_NAMES.put("PostgreSQL", "PostgreSQL");
        CONNECTOR_NAMES.put("SQLite", "SQLite");
        CONNECTOR_NAMES.put("SqlServer", "SQL Server");
        CONNECTOR_NAMES.put("Oracle", "Oracle");
        CONNECTOR_NAMES.put("Dameng", "达梦 DM8");
        CONNECTOR_NAMES.put("Kingbase", "人大金仓 Kingbase");
        CONNECTOR_NAMES.put("OceanBase", "OceanBase");
        CONNECTOR_NAMES.put("OceanBaseOracle", "OceanBase(Oracle模式)");
        CONNECTOR_NAMES.put("TiDB", "TiDB");
        CONNECTOR_NAMES.put("Doris", "Apache Doris");
        CONNECTOR_NAMES.put("StarRocks", "StarRocks");
        CONNECTOR_NAMES.put("ClickHouse", "ClickHouse");
        CONNECTOR_NAMES.put("DuckDB", "DuckDB");
        CONNECTOR_NAMES.put("H2", "H2");
        CONNECTOR_NAMES.put("MongoDB", "MongoDB");
        CONNECTOR_NAMES.put("Redis", "Redis");
        CONNECTOR_NAMES.put("Elasticsearch", "Elasticsearch");
        CONNECTOR_NAMES.put("Kafka", "Kafka");
        CONNECTOR_NAMES.put("RocketMQ", "RocketMQ");
        CONNECTOR_NAMES.put("File", "文件");
        CONNECTOR_NAMES.put("Http", "HTTP");
    }

    @RequestMapping("")
    public String index(ModelMap model) {
        model.put("plugins", pluginService.listPlugins());
        model.put("connectors", listConnectors());
        model.put("version", appConfig.getVersion());
        return "plugin/list";
    }

    /**
     * 已加载的连接器（JDBC 驱动）列表
     */
    private List<Map<String, String>> listConnectors() {
        List<Map<String, String>> result = new ArrayList<>();
        for (String type : connectorService.getConnectorTypeAll()) {
            Map<String, String> m = new HashMap<>();
            m.put("type", type);
            m.put("name", CONNECTOR_NAMES.getOrDefault(type, type));
            result.add(m);
        }
        return result;
    }

    @PostMapping(value = "/upload")
    @ResponseBody
    public RestResult upload(MultipartFile[] files) {
        try {
            if (files != null && files.length > 0) {
                MultipartFile file = null;
                String filePath = pluginService.getPluginPath();
                FileUtils.forceMkdir(new File(filePath));
                for (int i = 0; i < files.length; i++) {
                    file = files[i];
                    if (file != null) {
                        String filename = file.getOriginalFilename();
                        pluginService.checkFileSuffix(filename);
                        File dest = new File(filePath + filename);
                        FileUtils.deleteQuietly(dest);
                        FileUtils.copyInputStreamToFile(file.getInputStream(), dest);
                    }
                }
                pluginService.loadPlugins();
            }
            return RestResult.restSuccess("ok");
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            return RestResult.restFail(e.getMessage());
        }
    }

    @GetMapping("/download")
    public void download(HttpServletResponse response, String name) {
        String fileName = String.format("DBDataSync-%s-%s.jar", name, appConfig.getVersion());
        File file = new File(pluginService.getLibraryPath() + fileName);
        if (!file.exists()) {
            write(response, RestResult.restFail("Could not find file", 404));
            return;
        }

        response.setHeader("content-type", "application/octet-stream");
        response.setHeader("Content-Disposition", String.format("attachment; filename=%s", fileName));
        response.setContentType("application/octet-stream");
        BufferedInputStream bis = null;
        OutputStream outputStream = null;
        try {
            outputStream = response.getOutputStream();
            bis = new BufferedInputStream(new FileInputStream(file));
            byte[] buff = new byte[2048];
            int read = bis.read(buff);
            while (read != -1) {
                outputStream.write(buff, 0, buff.length);
                outputStream.flush();
                read = bis.read(buff);
            }
        } catch (IOException e) {
            logger.error(e.getMessage());
        } finally {
            IOUtils.closeQuietly(bis);
            IOUtils.closeQuietly(outputStream);
        }
    }

    /**
     * 响应
     *
     * @param response
     * @param result
     */
    private void write(HttpServletResponse response, RestResult result) {
        PrintWriter out = null;
        try {
            response.setContentType("application/json;charset=utf-8");
            response.setStatus(result.getStatus());
            out = response.getWriter();
            out.write(JsonUtil.objToJson(result));
            out.flush();
        } catch (IOException e) {
            logger.error(e.getMessage());
        } finally {
            if (null != out) {
                out.close();
            }
        }
    }
}
