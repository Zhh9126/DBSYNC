/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.repush;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账明细聚合器：将零散的 {@link ReconcileDetail} 按业务主键聚合为 {@link AggregatedRepushUnit}。
 * <p>
 * 规则：
 * <ul>
 * <li>按 businessKey 分组，使用 LinkedHashMap 保持明细首次出现的顺序</li>
 * <li>businessKey 为 null 或空串的明细不参与合并，每条独立成一个单元</li>
 * <li>单元内 mergedPayload 由各组内明细 payload 按出现顺序以 "\n" 拼接</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class DetailAggregator {

    /** payload 拼接分隔符 */
    private static final String PAYLOAD_SEPARATOR = "\n";

    /**
     * 聚合对账明细。
     *
     * @param details 对账明细列表，允许为 null 或空（返回空列表）
     * @return 聚合后的重推单元列表，保持首次出现顺序
     */
    public List<AggregatedRepushUnit> aggregate(List<ReconcileDetail> details) {
        List<AggregatedRepushUnit> units = new ArrayList<AggregatedRepushUnit>();
        if (details == null || details.isEmpty()) {
            return units;
        }

        // businessKey -> 组内明细，LinkedHashMap 保持首次出现顺序
        Map<String, List<ReconcileDetail>> grouped = new LinkedHashMap<String, List<ReconcileDetail>>();
        // null/空 businessKey 的明细各自独立成单元，不合并
        for (ReconcileDetail detail : details) {
            if (isEmptyKey(detail.getBusinessKey())) {
                units.add(buildUnit(detail));
            } else {
                List<ReconcileDetail> group = grouped.get(detail.getBusinessKey());
                if (group == null) {
                    group = new ArrayList<ReconcileDetail>();
                    grouped.put(detail.getBusinessKey(), group);
                }
                group.add(detail);
            }
        }

        for (Map.Entry<String, List<ReconcileDetail>> entry : grouped.entrySet()) {
            List<ReconcileDetail> group = entry.getValue();
            units.add(new AggregatedRepushUnit(entry.getKey(), group, joinPayloads(group)));
        }
        return units;
    }

    /**
     * 将 null/空 businessKey 的单条明细包装为独立单元。
     */
    private AggregatedRepushUnit buildUnit(ReconcileDetail detail) {
        List<ReconcileDetail> single = new ArrayList<ReconcileDetail>(1);
        single.add(detail);
        return new AggregatedRepushUnit(detail.getBusinessKey(), single, joinPayloads(single));
    }

    /**
     * 将组内明细 payload 按顺序以 "\n" 拼接；null payload 按空串处理。
     */
    private String joinPayloads(List<ReconcileDetail> group) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < group.size(); i++) {
            if (i > 0) {
                sb.append(PAYLOAD_SEPARATOR);
            }
            String payload = group.get(i).getPayload();
            if (payload != null) {
                sb.append(payload);
            }
        }
        return sb.toString();
    }

    private boolean isEmptyKey(String key) {
        return key == null || key.isEmpty();
    }
}
