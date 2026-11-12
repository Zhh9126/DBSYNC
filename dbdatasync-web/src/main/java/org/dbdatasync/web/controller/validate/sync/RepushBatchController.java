package org.dbdatasync.web.controller.validate.sync;

import org.dbdatasync.biz.ValidateSyncService;
import org.dbdatasync.biz.repush.AggregatedRepushService;
import org.dbdatasync.biz.repush.RepushRunSummary;
import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.web.controller.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨明细聚合批量重推（Wave17 运行时接线）。
 *
 * <p>与 {@code /validate-sync/reviseDiffItems}（单明细内多条目批量）互补：
 * 本端点接收<b>跨明细</b>的差异条目清单，按业务键聚合 → 分批 → 逐明细回调既有
 * {@code ValidateSyncService.reviseDiffItems} 通道执行，输出批次级汇总。
 * 输入 items 形如 {@code [{"taskId":"t1","detailId":"d1","itemIndex":0,"businessKey":"BK-A"}]}；
 * businessKey 为空时该条目独立成组。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Controller
@RequestMapping("/validate-sync")
public class RepushBatchController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private ValidateSyncService validateSyncService;

    /** 跨明细聚合批量重推 */
    @PostMapping("/reviseDiffBatchAggregated")
    @ResponseBody
    public RestResult reviseDiffBatchAggregated(@RequestBody Map<String, Object> body) {
        try {
            List<AggregatedRepushService.ItemInput> items = parseItems(body);
            if (items.isEmpty()) {
                return RestResult.restFail("items 不能为空");
            }
            int maxBatchSize = (int) longOf(body.get("maxBatchSize"), 50L);
            boolean abortOnFailure = !Boolean.FALSE.equals(body.get("abortOnFailure"));

            final Map<String, Integer> perItemResults = new LinkedHashMap<String, Integer>();
            AggregatedRepushService.DetailRepushCallback callback =
                    new AggregatedRepushService.DetailRepushCallback() {
                        @Override
                        public int repush(String taskId, String detailId, List<Integer> itemIndexes)
                                throws Exception {
                            // 复用既有单明细批量重推通道；失败由其抛异常（该批记为失败）
                            validateSyncService.reviseDiffItems(taskId, detailId, itemIndexes);
                            return itemIndexes.size();
                        }
                    };

            RepushRunSummary summary = new AggregatedRepushService()
                    .runAggregated(items, maxBatchSize, callback, abortOnFailure, perItemResults);

            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("totalBatches", summary.getTotalBatches());
            data.put("executedBatches", summary.getExecutedBatches());
            data.put("successBatches", summary.getSuccessBatches());
            data.put("failedBatches", summary.getFailedBatches());
            data.put("failedBatchIndexes", summary.getFailedBatchIndexes());
            data.put("pushedRows", summary.getPushedRows());
            data.put("aborted", summary.isAborted());
            data.put("perDetailResults", perItemResults);
            return RestResult.restSuccess(data);
        } catch (IllegalArgumentException e) {
            return RestResult.restFail(e.getMessage());
        } catch (Exception e) {
            logger.error("aggregated repush failed", e);
            return RestResult.restFail("聚合批量重推失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AggregatedRepushService.ItemInput> parseItems(Map<String, Object> body) {
        List<AggregatedRepushService.ItemInput> items = new ArrayList<AggregatedRepushService.ItemInput>();
        Object raw = body == null ? null : body.get("items");
        if (!(raw instanceof List)) {
            return items;
        }
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) item;
            String taskId = str(map.get("taskId"));
            String detailId = str(map.get("detailId"));
            if (taskId == null || taskId.trim().isEmpty() || detailId == null || detailId.trim().isEmpty()) {
                throw new IllegalArgumentException("items 中 taskId 与 detailId 不能为空");
            }
            int itemIndex = (int) longOf(map.get("itemIndex"), -1L);
            if (itemIndex < 0) {
                throw new IllegalArgumentException("items 中 itemIndex 不能为负");
            }
            String businessKey = str(map.get("businessKey"));
            items.add(new AggregatedRepushService.ItemInput(taskId.trim(), detailId.trim(),
                    itemIndex, businessKey == null ? "" : businessKey.trim()));
        }
        return items;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longOf(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
