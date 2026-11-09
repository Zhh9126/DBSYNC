package org.dbdatasync.sdk.checkpoint;

import org.dbdatasync.sdk.model.ChangedOffset;
import org.dbdatasync.sdk.replay.ReplayConfirmation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave23 P0-B / P1 离线一致性测试：幂等键稳定性、位点原子提交、
 * 崩溃恢复（账本丢失但位点文件仍在）、重放结果确认。
 */
public class Wave23CheckpointTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("dbdatasync-wave23-cp").toFile();
        Checkpoints.reset();
        ReplayConfirmation.clear();
    }

    @After
    public void tearDown() {
        Checkpoints.reset();
        ReplayConfirmation.clear();
        deleteRecursively(dir);
    }

    @Test
    public void idempotencyKeyIsStableAcrossMapInsertionOrder() {
        Map<String, Object> a = new HashMap<>();
        a.put("id", 7);
        a.put("name", "张三");
        a.put("amount", 12.5d);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("amount", 12.5d);
        b.put("name", "张三");
        b.put("id", 7);

        String k1 = IdempotencyKey.of("m1", "mysql-bin.000001", 1200L, 3L, "t_order", "UPDATE", a);
        String k2 = IdempotencyKey.of("m1", "mysql-bin.000001", 1200L, 3L, "t_order", "UPDATE", b);
        assertEquals("Map 遍历顺序不得影响幂等键", k1, k2);
        assertEquals(64, k1.length());
    }

    @Test
    public void idempotencyKeySeparatesRowsAndPositions() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1);
        String base = IdempotencyKey.of("m1", "bin.1", 10L, 1L, "t", "INSERT", row);
        assertFalse(base.equals(IdempotencyKey.of("m1", "bin.1", 11L, 1L, "t", "INSERT", row)));
        assertFalse(base.equals(IdempotencyKey.of("m1", "bin.1", 10L, 2L, "t", "INSERT", row)));
        Map<String, Object> changed = new HashMap<>(row);
        changed.put("name", "x");
        assertFalse(base.equals(IdempotencyKey.of("m1", "bin.1", 10L, 1L, "t", "INSERT", changed)));
    }

    @Test
    public void fileCheckpointStoreRoundTripAndRemove() {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        String key = IdempotencyKey.of("m1", "bin.1", 10L, 1L, "t", "INSERT", null);
        CheckpointRecord record = new CheckpointRecord("m1", "bin.1", "10", 1L,
                1700000000000L, ChangedOffset.EVENT_TIME_SOURCE, key);
        store.save(record);

        CheckpointRecord loaded = store.load("m1");
        assertNotNull(loaded);
        assertEquals("m1", loaded.getMetaId());
        assertEquals("bin.1", loaded.getFileName());
        assertEquals("10", loaded.getPosition());
        assertEquals(1L, loaded.getSeq());
        assertEquals(1700000000000L, loaded.getSourceEventTime());
        assertEquals(ChangedOffset.EVENT_TIME_SOURCE, loaded.getEventTimeSource());
        assertEquals(key, loaded.getIdempotencyKey());

        store.remove("m1");
        assertNull(store.load("m1"));
    }

    @Test
    public void commitThenDuplicateAndCrashRecovery() {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        IdempotencyLedger ledger = new IdempotencyLedger(new File(dir, "idempotency.log"), 1000);
        TransactionalCheckpoint cp = new TransactionalCheckpoint(store, ledger);
        String key = IdempotencyKey.of("m1", "bin.1", 10L, 1L, "t", "INSERT", "row-1");

        assertFalse("首次事件不是重复", cp.isDuplicate("m1", key));
        cp.commit(new CheckpointRecord("m1", "bin.1", "10", 1L, 0L,
                ChangedOffset.EVENT_TIME_UNKNOWN, key));
        assertTrue("提交后幂等账本应命中", cp.isDuplicate("m1", key));
        assertNotNull(cp.recover("m1"));

        // 模拟崩溃：内存账本丢失/清空，但位点文件仍在
        ledger.clear();
        assertFalse("账本已被清空", ledger.isDuplicate(key));
        assertTrue("位点文件 lastKey 命中应自动补记并判定重复", cp.isDuplicate("m1", key));
        assertTrue("补记后账本重新可用", ledger.isDuplicate(key));
    }

    @Test
    public void replayConfirmationOnlyDeletesOnExplicitSuccess() {
        ReplayConfirmation.begin("m1", "r1");
        assertFalse("尚未确认时不得删除明细", ReplayConfirmation.awaitSuccess("m1", "r1", 30L));
        ReplayConfirmation.end("m1", "r1");

        ReplayConfirmation.begin("m1", "r2");
        ReplayConfirmation.mark("m1", "r2", false);
        assertFalse("明确写入失败时不得删除明细", ReplayConfirmation.awaitSuccess("m1", "r2", 100L));
        ReplayConfirmation.end("m1", "r2");

        ReplayConfirmation.begin("m1", "r3");
        ReplayConfirmation.mark("m1", "r3", true);
        assertTrue("只有明确成功才允许删除明细", ReplayConfirmation.awaitSuccess("m1", "r3", 100L));
        ReplayConfirmation.end("m1", "r3");
        assertEquals(0, ReplayConfirmation.pending());
    }

    @Test
    public void checkpointProviderTakesEffectImmediately() {
        assertFalse(Checkpoints.isEnabled());
        final boolean[] enabled = {false};
        Checkpoints.installProvider(() -> new CheckpointSettings(enabled[0], dir.getAbsolutePath(), 100));
        assertFalse(Checkpoints.isEnabled());
        enabled[0] = true;
        assertTrue("配置保存后应立即生效，无需重启", Checkpoints.isEnabled());
        assertNotNull(Checkpoints.coordinator());
        enabled[0] = false;
        assertNull(Checkpoints.coordinator());
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }
}
