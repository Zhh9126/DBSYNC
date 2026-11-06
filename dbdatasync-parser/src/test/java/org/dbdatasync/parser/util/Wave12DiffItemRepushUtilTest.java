package org.dbdatasync.parser.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Wave12 差异条目一键重推工具类单元测试（≥12 项）。
 *
 * <p>覆盖：selectItem 四形态归一化 + 全部失败分支、isRepushable 白名单、
 * singleItemContent 原引用、nextFixedTotal 修复计数（Q4）、isDone 边界。</p>
 */
public class Wave12DiffItemRepushUtilTest {

    private static Map<String, Object> item(String type, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        if (msg != null) {
            m.put("msg", msg);
        }
        return m;
    }

    private static String illegalMessage(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        fail("预期抛出 IllegalArgumentException");
        return null;
    }

    // ---------- selectItem ----------

    @Test
    public void testSelectItemFromList() {
        Map<String, Object> a = item("rowData", "目标缺失");
        Map<String, Object> b = item("rowData", "数据不一致");
        List<Map<String, Object>> content = Arrays.asList(a, b);
        assertSame(a, DiffItemRepushUtil.selectItem(content, 0));
        assertSame(b, DiffItemRepushUtil.selectItem(content, 1));
    }

    @Test
    public void testSelectItemFromMapDataForm() {
        Map<String, Object> a = item("rowData", "目标缺失");
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("data", Arrays.asList(a));
        assertSame(a, DiffItemRepushUtil.selectItem(wrapper, 0));
    }

    @Test
    public void testSelectItemFromJsonArrayString() {
        Map<String, Object> got = DiffItemRepushUtil.selectItem(
                "[{\"type\":\"rowData\",\"msg\":\"目标缺失\"},{\"type\":\"tableSchema\",\"msg\":\"x\"}]", 0);
        assertEquals("rowData", got.get("type"));
        assertEquals("目标缺失", got.get("msg"));
    }

    @Test
    public void testSelectItemFromJsonObjectString() {
        Map<String, Object> got = DiffItemRepushUtil.selectItem(
                "{\"data\":[{\"type\":\"rowData\",\"msg\":\"数据不一致\"}]}", 0);
        assertEquals("rowData", got.get("type"));
    }

    @Test
    public void testSelectItemNegativeIndex() {
        List<Map<String, Object>> content = Arrays.asList(item("rowData", "x"));
        assertEquals("差异序号非法", illegalMessage(() -> DiffItemRepushUtil.selectItem(content, -1)));
        // 负数优先级最高：即便 content 为空也先报非法
        assertEquals("差异序号非法", illegalMessage(() -> DiffItemRepushUtil.selectItem(null, -5)));
    }

    @Test
    public void testSelectItemOverMax() {
        List<Map<String, Object>> content = Arrays.asList(item("rowData", "x"));
        assertEquals("差异序号超出范围", illegalMessage(
                () -> DiffItemRepushUtil.selectItem(content, DiffItemRepushUtil.MAX_ITEM_INDEX + 1)));
    }

    @Test
    public void testSelectItemEmptyContent() {
        String msg = "该明细无差异数据";
        assertEquals(msg, illegalMessage(() -> DiffItemRepushUtil.selectItem(null, 0)));
        assertEquals(msg, illegalMessage(() -> DiffItemRepushUtil.selectItem("", 0)));
        assertEquals(msg, illegalMessage(() -> DiffItemRepushUtil.selectItem("   ", 0)));
        assertEquals(msg, illegalMessage(() -> DiffItemRepushUtil.selectItem(new ArrayList<Object>(), 0)));
        assertEquals(msg, illegalMessage(() -> DiffItemRepushUtil.selectItem(new HashMap<String, Object>(), 0)));
    }

    @Test
    public void testSelectItemOutOfBounds() {
        List<Map<String, Object>> content = Arrays.asList(item("rowData", "a"), item("rowData", "b"));
        assertEquals("差异序号超出范围（共 2 条）",
                illegalMessage(() -> DiffItemRepushUtil.selectItem(content, 2)));
        assertEquals("差异序号超出范围（共 2 条）",
                illegalMessage(() -> DiffItemRepushUtil.selectItem(content, 99)));
    }

    @Test
    public void testSelectItemNonMapItem() {
        List<Object> mixed = new ArrayList<>();
        mixed.add(item("rowData", "a"));
        mixed.add("I-am-not-a-map");
        assertEquals("差异条目格式异常", illegalMessage(() -> DiffItemRepushUtil.selectItem(mixed, 1)));
    }

    @Test
    public void testSelectItemBadJsonFallback() {
        String msg = "该明细无差异数据";
        assertEquals(msg, illegalMessage(() -> DiffItemRepushUtil.selectItem("[not-json", 0)));
        assertEquals(msg, illegalMessage(() -> DiffItemRepushUtil.selectItem("{bad", 0)));
        assertEquals(msg, illegalMessage(() -> DiffItemRepushUtil.selectItem("garbage", 0)));
        // 根为 { 的合法对象串（无 data）：不得走 parseArray，降级为空
        assertEquals(msg, illegalMessage(() -> DiffItemRepushUtil.selectItem("{\"other\":1}", 0)));
    }

    // ---------- isRepushable ----------

    @Test
    public void testIsRepushableRowData() {
        assertTrue(DiffItemRepushUtil.isRepushable(item("rowData", "目标缺失")));
        assertTrue(DiffItemRepushUtil.isRepushable(item("rowData", null)));
    }

    @Test
    public void testIsRepushableRejectsNonRowDataAndNull() {
        assertFalse(DiffItemRepushUtil.isRepushable(item("tableSchema", "x")));
        assertFalse(DiffItemRepushUtil.isRepushable(item("index", "x")));
        assertFalse(DiffItemRepushUtil.isRepushable(item(null, "x")));
        assertFalse(DiffItemRepushUtil.isRepushable(null));
    }

    @Test
    public void testIsRepushableRejectsFixedMarkers() {
        assertFalse(DiffItemRepushUtil.isRepushable(item("rowData", "数据不一致(已订正)")));
        assertFalse(DiffItemRepushUtil.isRepushable(item("rowData", "数据不一致(订正失败:影响行数为0)")));
    }

    // ---------- singleItemContent ----------

    @Test
    public void testSingleItemContentSameReference() {
        Map<String, Object> a = item("rowData", "目标缺失");
        List<Map<String, Object>> wrapped = DiffItemRepushUtil.singleItemContent(a);
        assertEquals(1, wrapped.size());
        assertSame(a, wrapped.get(0));
    }

    // ---------- nextFixedTotal ----------

    @Test
    public void testNextFixedTotalRepairedZero() {
        assertEquals(3L, DiffItemRepushUtil.nextFixedTotal(10L, 3L, 0));
        assertEquals(0L, DiffItemRepushUtil.nextFixedTotal(0L, 0L, 0));
        assertEquals(7L, DiffItemRepushUtil.nextFixedTotal(10L, 7L, 0));
    }

    @Test
    public void testNextFixedTotalRepairedPositive() {
        assertEquals(5L, DiffItemRepushUtil.nextFixedTotal(10L, 3L, 2));
        assertEquals(1L, DiffItemRepushUtil.nextFixedTotal(10L, 0L, 1));
    }

    @Test
    public void testNextFixedTotalCapped() {
        assertEquals(4L, DiffItemRepushUtil.nextFixedTotal(4L, 3L, 5));
        assertEquals(10L, DiffItemRepushUtil.nextFixedTotal(10L, 9L, 5));
    }

    @Test
    public void testNextFixedTotalNonPositiveDiffNotNegative() {
        assertEquals(0L, DiffItemRepushUtil.nextFixedTotal(0L, 3L, 1));
        assertEquals(0L, DiffItemRepushUtil.nextFixedTotal(-5L, 2L, 1));
        assertTrue(DiffItemRepushUtil.nextFixedTotal(-5L, 2L, 1) >= 0);
    }

    // ---------- isDone ----------

    @Test
    public void testIsDoneBoundary() {
        assertTrue(DiffItemRepushUtil.isDone(5L, 5L));
        assertTrue(DiffItemRepushUtil.isDone(5L, 6L));
        assertFalse(DiffItemRepushUtil.isDone(5L, 4L));
        assertTrue(DiffItemRepushUtil.isDone(0L, 0L));
    }

    @Test
    public void testMaxItemIndexConstant() {
        assertEquals(100000, DiffItemRepushUtil.MAX_ITEM_INDEX);
    }
}
