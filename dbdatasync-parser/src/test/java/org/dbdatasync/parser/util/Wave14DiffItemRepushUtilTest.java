package org.dbdatasync.parser.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Wave14 差异条目批量重推——批量选择辅助单元测试。
 *
 * <p>覆盖：selectRepushableItems 的有效收集、越界/已订正/非行数据降级、空/空列表/空输入安全。</p>
 */
public class Wave14DiffItemRepushUtilTest {

    private static Map<String, Object> item(String type, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        if (msg != null) {
            m.put("msg", msg);
        }
        return m;
    }

    @Test
    public void testSelectAllValid() {
        Map<String, Object> a = item("rowData", "目标缺失");
        Map<String, Object> b = item("rowData", "数据不一致");
        List<Map<String, Object>> content = Arrays.asList(a, b);
        List<Map<String, Object>> valid = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        DiffItemRepushUtil.selectRepushableItems(content, Arrays.asList(0, 1), valid, failures);
        assertEquals(2, valid.size());
        assertEquals(0, failures.size());
        assertTrue(valid.contains(a));
        assertTrue(valid.contains(b));
    }

    @Test
    public void testSelectWithOutOfRangeIndex() {
        Map<String, Object> a = item("rowData", "目标缺失");
        List<Map<String, Object>> content = Arrays.asList(a);
        List<Map<String, Object>> valid = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        DiffItemRepushUtil.selectRepushableItems(content, Arrays.asList(0, 5), valid, failures);
        // 0 合法、5 越界
        assertEquals(1, valid.size());
        assertEquals(1, failures.size());
        assertEquals(5, failures.get(0).get("index"));
        String reason = String.valueOf(failures.get(0).get("reason"));
        assertTrue("越界原因应包含'超出范围'，实际=" + reason, reason.contains("超出范围"));
    }

    @Test
    public void testSelectWithAlreadyCorrected() {
        Map<String, Object> a = item("rowData", "目标缺失");
        Map<String, Object> b = item("rowData", "目标缺失(已订正)");
        List<Map<String, Object>> content = Arrays.asList(a, b);
        List<Map<String, Object>> valid = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        DiffItemRepushUtil.selectRepushableItems(content, Arrays.asList(0, 1), valid, failures);
        assertEquals(1, valid.size());
        assertEquals(1, failures.size());
        assertEquals(1, failures.get(0).get("index"));
        String reason = String.valueOf(failures.get(0).get("reason"));
        assertTrue("已订正原因应包含'不可重推'，实际=" + reason, reason.contains("不可重推"));
    }

    @Test
    public void testSelectWithNonRowData() {
        Map<String, Object> a = item("rowData", "目标缺失");
        Map<String, Object> b = item("tableSchema", "类型不一致");
        List<Map<String, Object>> content = Arrays.asList(a, b);
        List<Map<String, Object>> valid = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        DiffItemRepushUtil.selectRepushableItems(content, Arrays.asList(0, 1), valid, failures);
        assertEquals(1, valid.size());
        assertEquals(1, failures.size());
        assertEquals(1, failures.get(0).get("index"));
    }

    @Test
    public void testSelectWithFailedMsg() {
        Map<String, Object> a = item("rowData", "目标缺失(订正失败:影响行数为0)");
        List<Map<String, Object>> content = Arrays.asList(a);
        List<Map<String, Object>> valid = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        DiffItemRepushUtil.selectRepushableItems(content, Arrays.asList(0), valid, failures);
        assertEquals(0, valid.size());
        assertEquals(1, failures.size());
    }

    @Test
    public void testSelectFromMapDataForm() {
        Map<String, Object> a = item("rowData", "目标缺失");
        Map<String, Object> b = item("rowData", "数据不一致");
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("data", Arrays.asList(a, b));
        List<Map<String, Object>> valid = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        DiffItemRepushUtil.selectRepushableItems(wrapper, Arrays.asList(0, 1), valid, failures);
        assertEquals(2, valid.size());
        assertEquals(0, failures.size());
    }

    @Test
    public void testSelectEmptyIndexes() {
        Map<String, Object> a = item("rowData", "目标缺失");
        List<Map<String, Object>> content = Arrays.asList(a);
        List<Map<String, Object>> valid = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        DiffItemRepushUtil.selectRepushableItems(content, new ArrayList<Integer>(), valid, failures);
        assertEquals(0, valid.size());
        assertEquals(0, failures.size());
    }

    @Test
    public void testSelectNullIndexes() {
        Map<String, Object> a = item("rowData", "目标缺失");
        List<Map<String, Object>> content = Arrays.asList(a);
        List<Map<String, Object>> valid = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        DiffItemRepushUtil.selectRepushableItems(content, null, valid, failures);
        assertEquals(0, valid.size());
        assertEquals(0, failures.size());
    }

    @Test
    public void testSelectNullContent() {
        List<Map<String, Object>> valid = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        DiffItemRepushUtil.selectRepushableItems(null, Arrays.asList(0), valid, failures);
        assertEquals(0, valid.size());
        assertEquals(1, failures.size());
    }
}
