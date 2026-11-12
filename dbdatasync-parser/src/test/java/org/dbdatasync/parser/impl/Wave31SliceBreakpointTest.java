package org.dbdatasync.parser.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.dbdatasync.parser.model.TableSyncProgress;
import org.dbdatasync.parser.util.FullTableProgressUtil;
import org.junit.Test;

/**
 * Wave31：分片断点隔离回归。
 *
 * <p>覆盖分片进度键与边界串格式、TableSyncProgress.bounds 的 JSON 往返
 * （含旧版本无 bounds 字段的向后兼容）、以及 FullTableProgressUtil 对
 * 分片键的存取与 done 判定。</p>
 */
public class Wave31SliceBreakpointTest {

    @Test
    public void sliceKeyIsDistinctPerIndex() {
        String group = "grp-abc";
        assertEquals("grp-abc#s0", ParserComponentImpl.sliceProgressKey(group, 0));
        assertEquals("grp-abc#s3", ParserComponentImpl.sliceProgressKey(group, 3));
        assertNotEquals(ParserComponentImpl.sliceProgressKey(group, 0), group);
        assertNotEquals(ParserComponentImpl.sliceProgressKey(group, 1), ParserComponentImpl.sliceProgressKey(group, 2));
    }

    @Test
    public void intervalBoundsFormat() {
        assertEquals("10~20", ParserComponentImpl.intervalBounds(10L, 20L));
        assertEquals("0~9223372036854775807", ParserComponentImpl.intervalBounds(0L, Long.MAX_VALUE));
        assertNotEquals(ParserComponentImpl.intervalBounds(1L, 100L), ParserComponentImpl.intervalBounds(1L, 200L));
    }

    @Test
    public void boundsRoundTripAndLegacyCompat() {
        // 新格式往返
        TableSyncProgress p = new TableSyncProgress();
        p.setPageIndex(7);
        p.setCursor("DBDS-CURSOR-V2:1|2");
        p.setDone(false);
        p.setBounds("10~20");
        Map<String, TableSyncProgress> map = new HashMap<>();
        map.put("g1#s0", p);
        Map<String, String> snapshot = new HashMap<>();
        FullTableProgressUtil.save(snapshot, map);
        Map<String, TableSyncProgress> loaded = FullTableProgressUtil.load(snapshot);
        assertEquals("10~20", loaded.get("g1#s0").getBounds());
        assertEquals(7, loaded.get("g1#s0").getPageIndex());
        assertFalse(loaded.get("g1#s0").isDone());

        // 旧版本 JSON（无 bounds 字段）反序列化安全
        Map<String, TableSyncProgress> legacy = FullTableProgressUtil.load(
                new java.util.HashMap<String, String>() {{
                    put("tableProgress", "{\"t1\":{\"pageIndex\":3,\"cursor\":\"1,2\",\"done\":false}}");
                }});
        TableSyncProgress t1 = legacy.get("t1");
        assertTrue(t1 != null && t1.getPageIndex() == 3);
        assertEquals("", t1.getBounds());
    }

    @Test
    public void doneSliceSkippedByProgressUtil() {
        Map<String, String> snapshot = new HashMap<>();
        TableSyncProgress done = new TableSyncProgress();
        done.setDone(true);
        done.setBounds("5~9");
        FullTableProgressUtil.put(snapshot, "g1#s1", done);

        TableSyncProgress running = new TableSyncProgress();
        running.setPageIndex(4);
        running.setCursor("9");
        running.setBounds("10~20");
        FullTableProgressUtil.put(snapshot, "g1#s2", running);

        assertTrue(FullTableProgressUtil.isDone(snapshot, "g1#s1"));
        assertFalse(FullTableProgressUtil.isDone(snapshot, "g1#s2"));
        assertFalse(FullTableProgressUtil.isDone(snapshot, "g1#s9"));
        assertEquals(4, FullTableProgressUtil.getOrInit(snapshot, "g1#s2").getPageIndex());
    }

    @Test
    public void putOverwritesProgressButPreservesIndependentKeys() {
        Map<String, String> snapshot = new HashMap<>();
        TableSyncProgress s0 = new TableSyncProgress();
        s0.setDone(true);
        s0.setBounds("1~2");
        FullTableProgressUtil.put(snapshot, "g#s0", s0);

        TableSyncProgress s1 = new TableSyncProgress();
        s1.setPageIndex(5);
        s1.setCursor("42");
        s1.setBounds("2~3");
        FullTableProgressUtil.put(snapshot, "g#s1", s1);

        // 更新 s1 不影响 s0（分片断点互不覆盖的核心断言）
        TableSyncProgress s1b = new TableSyncProgress();
        s1b.setPageIndex(6);
        s1b.setCursor("43");
        s1b.setBounds("2~3");
        FullTableProgressUtil.put(snapshot, "g#s1", s1b);

        Map<String, TableSyncProgress> loaded = FullTableProgressUtil.load(snapshot);
        assertTrue(loaded.get("g#s0").isDone());
        assertEquals("1~2", loaded.get("g#s0").getBounds());
        assertEquals(6, loaded.get("g#s1").getPageIndex());
        assertEquals("43", loaded.get("g#s1").getCursor());
    }
}
