package org.dbdatasync.web.controller.contentoffload;

import org.dbdatasync.biz.storage.OversizedContentService;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.web.controller.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 超大内容外置（Wave17 运行时接线）——解除 65535 字节截断的非破坏式路径。
 *
 * <p>{@code /store}：内容 UTF-8 字节不超过 maxInlineBytes（默认 65535）时原样返回；
 * 超限分块外置并返回 {@code DBDS-REF:<refId>} 引用串。{@code /resolve}：引用串反解原文。
 * 既有 DATA 字段在超限时改存引用串，不改表结构。默认块存储为进程内存实现，
 * 持久化 ChunkStore 接入 task_detail 写入链路属后续专项。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Controller
@RequestMapping("/content-offload")
public class ContentOffloadController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private OversizedContentService oversizedContentService;

    /** 存储：内联直通或分块外置（content 必填，maxInlineBytes 可选） */
    @PostMapping("/store")
    @ResponseBody
    public RestResult store(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String content = params.get("content");
            if (content == null) {
                return RestResult.restFail("content 不能为空");
            }
            Integer maxInlineBytes = params.get("maxInlineBytes") == null
                    ? null : Integer.valueOf(params.get("maxInlineBytes").trim());
            oversizedContentService.configure(maxInlineBytes);
            String stored = oversizedContentService.store(content);
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("stored", stored);
            data.put("offloaded", oversizedContentService.isRef(stored));
            data.put("utf8Bytes", org.dbdatasync.biz.storage.OversizedContentOffloader.utf8Length(content));
            return RestResult.restSuccess(data);
        } catch (NumberFormatException e) {
            return RestResult.restFail("maxInlineBytes 数值非法");
        } catch (Exception e) {
            logger.error("content offload store failed", e);
            return RestResult.restFail("内容外置失败：" + e.getMessage());
        }
    }

    /** 反解：DBDS-REF 引用串还原原文；非引用原样返回 */
    @PostMapping("/resolve")
    @ResponseBody
    public RestResult resolve(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String stored = params.get("stored");
            if (stored == null || stored.isEmpty()) {
                return RestResult.restFail("stored 不能为空");
            }
            return RestResult.restSuccess(oversizedContentService.resolve(stored));
        } catch (IllegalStateException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("content offload resolve failed", e);
            return RestResult.restFail("内容反解失败：" + e.getMessage());
        }
    }

    /** 删除引用串对应的全部块 */
    @PostMapping("/delete")
    @ResponseBody
    public RestResult delete(HttpServletRequest request) {
        try {
            Map<String, String> params = getParams(request);
            String stored = params.get("stored");
            if (stored == null || stored.isEmpty()) {
                return RestResult.restFail("stored 不能为空");
            }
            oversizedContentService.delete(stored);
            return RestResult.restSuccess("deleted");
        } catch (Exception e) {
            logger.error("content offload delete failed", e);
            return RestResult.restFail("外置内容删除失败：" + e.getMessage());
        }
    }
}
