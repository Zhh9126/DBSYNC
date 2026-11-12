package org.dbdatasync.parser.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave12 差异条目单条重推工具类（纯静态、零 Spring 依赖、可直接单测）。
 *
 * <p>content 归一化与 biz 侧 {@code ReconciliationCsvBuilder.normalizeContent} 同语义：
 * List / Map.data / JSON 数组串 / JSON 对象串四形态。根为 {@code {} 的字符串绝不调用
 * {@code JSON.parseArray}（Wave10  BUG 根因）。</p>
 */
public final class DiffItemRepushUtil {

    public static final int MAX_ITEM_INDEX = 100000;

    private DiffItemRepushUtil() {
    }

    /**
     * 从 content 取第 itemIndex 项；任何失败抛 IllegalArgumentException 且消息可读。
     */
    public static Map<String, Object> selectItem(Object content, int itemIndex) {
        if (itemIndex < 0) {
            throw new IllegalArgumentException("差异序号非法");
        }
        if (itemIndex > MAX_ITEM_INDEX) {
            throw new IllegalArgumentException("差异序号超出范围");
        }
        List<?> items = normalizeContent(content);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("该明细无差异数据");
        }
        if (itemIndex >= items.size()) {
            throw new IllegalArgumentException("差异序号超出范围（共 " + items.size() + " 条）");
        }
        Object target = items.get(itemIndex);
        if (!(target instanceof Map)) {
            throw new IllegalArgumentException("差异条目格式异常");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) target;
        return item;
    }

    /**
     * 仅 rowData 且 msg 未含 "(已订正)" / "(订正失败" 才可重推。
     */
    public static boolean isRepushable(Map<String, Object> item) {
        if (item == null) {
            return false;
        }
        if (!"rowData".equals(item.get("type"))) {
            return false;
        }
        Object msgObj = item.get("msg");
        String msg = msgObj == null ? "" : String.valueOf(msgObj);
        return !msg.contains("(已订正)") && !msg.contains("(订正失败");
    }

    /**
     * 只包一层 List（不复制 item，保住 repairContent 的就地标记）。
     */
    public static List<Map<String, Object>> singleItemContent(Map<String, Object> item) {
        List<Map<String, Object>> list = new ArrayList<>(1);
        list.add(item);
        return list;
    }

    /**
     * 批量选择可重推条目（纯静态、零 DB，可直接单测）。
     *
     * <p>从 content 取出各下标对应 item 引用（共享 content 内引用，保住 repairContent 就地标记），
     * 校验可重推性：合法可重推项加入 {@code validItems}；非法下标 / 已订正 / 非行数据等不可重推项
     * 写入 {@code failures}（含 index 与 reason），不影响其余条目。</p>
     *
     * @param content     明细 content（四形态之一）
     * @param indexes     请求的差异条目下标（0-based，与对账报告「差异序号」一致）
     * @param validItems  输出：可重推 item 引用（共享引用）
     * @param failures    输出：不可重推 / 非法项 {index, reason}
     */
    public static void selectRepushableItems(Object content, List<Integer> indexes,
                                            List<Map<String, Object>> validItems,
                                            List<Map<String, Object>> failures) {
        if (indexes == null || validItems == null || failures == null) {
            return;
        }
        for (Integer raw : indexes) {
            int idx = raw == null ? -1 : raw;
            Map<String, Object> item;
            try {
                item = selectItem(content, idx);
            } catch (IllegalArgumentException e) {
                failures.add(failure(idx, e.getMessage()));
                continue;
            }
            if (!isRepushable(item)) {
                failures.add(failure(idx, "该差异条目不可重推（已订正或不可修）"));
                continue;
            }
            validItems.add(item);
        }
    }

    /**
     * 构造一条失败记录 {index, reason}。
     */
    private static Map<String, Object> failure(int index, String reason) {
        Map<String, Object> f = new HashMap<>(2);
        f.put("index", index);
        f.put("reason", reason);
        return f;
    }

    /**
     * repaired &gt; 0 时 min(diffTotal, fixedTotal + repaired)；否则原值不变（Q4）。
     * diffTotal &le; 0 时以 max(0, diffTotal) 封顶，结果不会为负。
     */
    public static long nextFixedTotal(long diffTotal, long fixedTotal, int repaired) {
        if (repaired <= 0) {
            return fixedTotal;
        }
        long cap = Math.max(0, diffTotal);
        long next = Math.min(cap, fixedTotal + repaired);
        return Math.max(0, next);
    }

    public static boolean isDone(long diffTotal, long fixedTotal) {
        return fixedTotal >= diffTotal;
    }

    /** content 四形态归一化为条目列表；解析异常与其它一律空 List。 */
    private static List<?> normalizeContent(Object content) {
        if (content == null) {
            return Collections.emptyList();
        }
        if (content instanceof List) {
            return (List<?>) content;
        }
        if (content instanceof Map) {
            Object data = ((Map<?, ?>) content).get("data");
            return data instanceof List ? (List<?>) data : Collections.emptyList();
        }
        if (content instanceof String) {
            String s = ((String) content).trim();
            if (s.isEmpty()) {
                return Collections.emptyList();
            }
            try {
                char first = s.charAt(0);
                if (first == '[') {
                    return JSON.parseArray(s);
                }
                if (first == '{') {
                    JSONObject obj = JSON.parseObject(s);
                    Object data = obj == null ? null : obj.get("data");
                    return data instanceof List ? (List<?>) data : Collections.emptyList();
                }
            } catch (Exception e) {
                return Collections.emptyList();
            }
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }
}
