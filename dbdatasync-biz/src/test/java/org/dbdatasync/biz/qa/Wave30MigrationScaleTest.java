package org.dbdatasync.biz.qa;

import org.dbdatasync.biz.slowsql.PersistentSlowSqlStore;
import org.dbdatasync.biz.slowsql.SlowSqlThreshold;
import org.dbdatasync.biz.util.ReconciliationCsvBuilder;
import org.dbdatasync.common.enums.CommonTaskStatusEnum;
import org.dbdatasync.common.util.BatchTaskUtil;
import org.dbdatasync.common.util.TaskSplitUtil;
import org.dbdatasync.sdk.enums.DatabaseMigrationDetailTypeEnum;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.util.PrimaryKeyUtil;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Wave30「大数据量迁移」离线回归：位点编码、批拆、流式对账导出、整库迁移开关、
 * 阶段进度语义、批量异常传播、慢 SQL 回放有界性。
 *
 * <p>全部为纯 JDK/JUnit 测试，不连数据库、不启 Spring、不改生产数据。</p>
 */
public class Wave30MigrationScaleTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---------- 断点位点：复合主键 / 逗号 / 前导零 / 时间类型 ----------

    @Test
    public void cursorRoundTripKeepsTypeAndSpecialChars() {
        Timestamp ts = Timestamp.valueOf("2026-01-01 00:00:00.0");
        Object[] original = new Object[]{"a,b", "00123", 42L, ts, null};
        String encoded = PrimaryKeyUtil.encodeCursors(original);
        assertTrue(encoded.startsWith(PrimaryKeyUtil.CURSOR_ENCODE_PREFIX));
        Object[] restored = PrimaryKeyUtil.getLastCursors(encoded);
        assertEquals(5, restored.length);
        assertEquals("a,b", restored[0]);
        assertEquals("00123", restored[1]);
        assertEquals(42L, restored[2]);
        assertEquals(ts, restored[3]);
        assertNull(restored[4]);
    }

    @Test
    public void legacyCursorStringKeepsLeadingZero() {
        Object[] restored = PrimaryKeyUtil.getLastCursors("00123,abc");
        assertEquals("00123", restored[0]);
        assertEquals("abc", restored[1]);
        assertEquals("", PrimaryKeyUtil.encodeCursors(new Object[0]));
        assertNull(PrimaryKeyUtil.getLastCursors(""));
    }

    // ---------- 批拆：正确性与不再 O(n^2) ----------

    @Test
    public void splitKeepsAllRowsInOrderAndCopiesBatches() {
        List<Integer> rows = new ArrayList<Integer>();
        for (int i = 0; i < 250; i++) {
            rows.add(i);
        }
        final List<List<Integer>> batches = new ArrayList<List<Integer>>();
        TaskSplitUtil.split(rows, 100, batches::add);
        assertEquals(3, batches.size());
        List<Integer> flat = new ArrayList<Integer>();
        for (List<Integer> batch : batches) {
            flat.addAll(batch);
        }
        assertEquals(rows, flat);
        // 子批必须是独立副本，清空源列表不影响已产出的子批
        rows.clear();
        assertEquals(100, batches.get(0).size());
    }

    // ---------- 对账报告：流式两遍扫描、无 5000 行截断 ----------

    @Test
    public void streamingCsvExportDoesNotCapDetailRows() throws Exception {
        final int total = 6000; // 超过旧实现 MAX_EXPORT_DETAIL_ROWS=5000
        ReconciliationCsvBuilder.DetailSource source = sink -> {
            for (int i = 0; i < total; i++) {
                sink.accept(map("id", "D-" + i, "diffTotal", 1, "content",
                        Collections.singletonList(map("type", "rowData", "key", "K" + i))));
            }
        };
        StringWriter out = new StringWriter();
        ReconciliationCsvBuilder.writeTo(out, "T-STREAM", source, 0L);
        String csv = out.toString();
        assertTrue(csv.contains("明细行数,6000\r\n"));
        assertTrue(csv.contains("导出差异条目数,6000\r\n"));
        assertTrue(csv.contains("完整性,完整\r\n"));
        int dataRows = 0;
        for (String line : csv.split("\r\n")) {
            if (line.startsWith("D-")) {
                dataRows++;
            }
        }
        assertEquals(total, dataRows);
    }

    @Test
    public void csvListOverloadStillMatchesStreamingOverload() throws Exception {
        List<Map<String, Object>> rows = Collections.singletonList(
                map("id", "D-1", "diffTotal", 1, "content",
                        Collections.singletonList(map("type", "rowData", "key", "K1"))));
        StringWriter a = new StringWriter();
        ReconciliationCsvBuilder.writeTo(a, "T", rows, 0L);
        StringWriter b = new StringWriter();
        ReconciliationCsvBuilder.writeTo(b, "T", (ReconciliationCsvBuilder.DetailSource) rows::forEach, 0L);
        assertEquals(b.toString(), a.toString());
    }

    // ---------- 整库迁移开关与阶段进度 ----------

    @Test
    public void legacyDatabaseSyncTaskDefaultsToCopyData() {
        // 存量 task.JSON 缺字段时保留默认 true，避免 Wave30 开关接入后历史任务突然不灌数据
        DatabaseSyncTask task = new DatabaseSyncTask();
        assertTrue(task.isEnableCopyData());
        task.setEnableCopyData(false);
        assertFalse(task.isEnableCopyData());
    }

    @Test
    public void failedTableIsNotCountedAsSchemaDone() {
        int done = CommonTaskStatusEnum.DONE.getCode();
        int ready = CommonTaskStatusEnum.READY.getCode();
        assertTrue(DatabaseMigrationDetailTypeEnum.isSchemaPhaseDone(
                DatabaseMigrationDetailTypeEnum.ROW_DATA.getCode(), done));
        assertFalse(DatabaseMigrationDetailTypeEnum.isSchemaPhaseDone(
                DatabaseMigrationDetailTypeEnum.ROW_DATA.getCode(), ready));
        assertFalse(DatabaseMigrationDetailTypeEnum.isDataPhaseDone(
                DatabaseMigrationDetailTypeEnum.TABLE_SCHEMA.getCode(), done));
    }

    // ---------- 批量任务：失败必须上抛，不得静默清空断点 ----------

    @Test
    public void batchFailureIsPropagated() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            BatchTaskUtil.executeWithAwait(Collections.singletonList("x"), pool,
                    v -> {
                        throw new IllegalStateException("boom");
                    }, LoggerFactory.getLogger(Wave30MigrationScaleTest.class));
            fail("批量任务失败必须上抛");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("批量任务执行失败"));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 慢 SQL：启动回放只保留尾部 N 行并顺手压缩文件 ----------

    @Test
    public void slowSqlReplayIsBoundedAndCompactsFile() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dbds-wave30-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        File file = new File(dir, "slow-sql.log");
        try {
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, false), StandardCharsets.UTF_8));
            for (int i = 0; i < 10000; i++) {
                w.write(i + "\t1500\t1\t1\tsrc\tSELECT " + i);
                w.newLine();
            }
            w.close();
            SlowSqlThreshold threshold = new SlowSqlThreshold();
            threshold.setMaxRecords(100);
            PersistentSlowSqlStore store = new PersistentSlowSqlStore(threshold, file);
            assertEquals(100, store.size());
            assertEquals("SELECT 9999", store.snapshot().get(store.size() - 1).getSql());
            // 超过 2 倍容量后回放会把文件压缩成尾部 100 行
            java.util.List<String> lines = java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            assertEquals(100, lines.size());
        } finally {
            if (file.exists()) {
                assertTrue(file.delete());
            }
            assertTrue(dir.delete());
        }
    }
}
