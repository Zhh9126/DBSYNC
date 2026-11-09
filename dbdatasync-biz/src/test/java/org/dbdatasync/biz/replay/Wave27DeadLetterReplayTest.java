/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.replay;

import org.dbdatasync.biz.BizException;
import org.dbdatasync.biz.DataSyncService;
import org.dbdatasync.biz.ReplicationHealthService;
import org.dbdatasync.biz.SystemConfigService;
import org.dbdatasync.biz.vo.DeadLetterVO;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.parser.model.SystemConfig;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Wave27：死信手动重放（replaySingle）单测。
 *
 * <p>覆盖：参数校验、记录不存在、非死信拒绝、正常重放、
 * 重放异常包装、手动重放不受自动编排退避限制（重放前后退避状态被清理）。</p>
 *
 * @author DBDataSync
 * @version 2.1.6
 * @date 2026-11-10
 */
public class Wave27DeadLetterReplayTest {

    private static final String META = "meta-1";

    private StubHealthService healthService;
    private StubDataSyncService dataSyncService;
    private DeadLetterReplayOrchestrator orchestrator;

    @Before
    public void setUp() {
        healthService = new StubHealthService();
        dataSyncService = new StubDataSyncService();
        orchestrator = new DeadLetterReplayOrchestrator();
        inject(orchestrator, "replicationHealthService", healthService);
        inject(orchestrator, "dataSyncService", dataSyncService);
        inject(orchestrator, "systemConfigService", new StubConfigService());
    }

    @Test
    public void blankParamsRejected() {
        try {
            orchestrator.replaySingle(null, "m1");
            fail("metaId 为空必须拒绝");
        } catch (BizException expected) {
            assertTrue(expected.getMessage().contains("不能为空"));
        }
        try {
            orchestrator.replaySingle(META, " ");
            fail("messageId 空白必须拒绝");
        } catch (BizException expected) {
            assertTrue(expected.getMessage().contains("不能为空"));
        }
        assertEquals(0, dataSyncService.syncCalls.size());
    }

    @Test
    public void missingRecordRejected() {
        try {
            orchestrator.replaySingle(META, "not-exist");
            fail("记录不存在必须拒绝");
        } catch (BizException expected) {
            assertTrue(expected.getMessage().contains("不存在"));
        }
        assertEquals(0, dataSyncService.syncCalls.size());
    }

    @Test
    public void plainRecordRejected() {
        healthService.rows.add(row("plain-1", false, false));
        try {
            orchestrator.replaySingle(META, "plain-1");
            fail("非死信记录必须拒绝");
        } catch (BizException expected) {
            assertTrue(expected.getMessage().contains("不允许重放"));
        }
        assertEquals(0, dataSyncService.syncCalls.size());
    }

    @Test
    public void deadLetterReplayed() {
        healthService.rows.add(row("dl-1", true, false));
        String message = orchestrator.replaySingle(META, "dl-1");
        assertTrue(message.contains("重放成功"));
        assertEquals(1, dataSyncService.syncCalls.size());
        assertEquals(META, dataSyncService.syncCalls.get(0).get("metaId"));
        assertEquals("dl-1", dataSyncService.syncCalls.get(0).get("messageId"));
    }

    @Test
    public void syncFailRecordReplayed() {
        healthService.rows.add(row("sf-1", false, true));
        String message = orchestrator.replaySingle(META, "sf-1");
        assertTrue(message.contains("重放成功"));
        assertEquals(1, dataSyncService.syncCalls.size());
    }

    @Test
    public void syncErrorWrapped() {
        healthService.rows.add(row("boom-1", true, false));
        dataSyncService.failOnNext = new RuntimeException("目标库连接中断");
        try {
            orchestrator.replaySingle(META, "boom-1");
            fail("sync 异常必须包装为 BizException");
        } catch (BizException expected) {
            assertTrue(expected.getMessage().contains("重放执行失败"));
            assertTrue(expected.getMessage().contains("目标库连接中断"));
        }
    }

    @Test
    public void manualReplayNotBlockedByAutoBackoff() {
        // 自动重放曾对同一条记录计满次数并设了退避：手动重放仍应立即执行
        healthService.rows.add(row("dl-backoff", true, false));
        // 模拟自动编排已记录失败：直接走一次手动失败 + 一次手动成功
        dataSyncService.failOnNext = new RuntimeException("瞬断");
        try {
            orchestrator.replaySingle(META, "dl-backoff");
            fail("首次手动重放应失败");
        } catch (BizException expected) {
            // 预期
        }
        dataSyncService.failOnNext = null;
        String message = orchestrator.replaySingle(META, "dl-backoff");
        assertTrue(message.contains("重放成功"));
        assertEquals(2, dataSyncService.syncCalls.size());
        assertEquals("手动重放不得遗留自动编排退避记录", 0, orchestrator.trackedCount());
    }

    private static DeadLetterVO row(String id, boolean dead, boolean fail) {
        DeadLetterVO vo = new DeadLetterVO();
        vo.setId(id);
        vo.setMetaId(META);
        vo.setMappingName("t-user-sync");
        vo.setTargetTableName("t_user");
        vo.setEvent("INSERT");
        vo.setError(dead ? "[DEAD_LETTER] 类型转换失败" : (fail ? "[SYNC_FAIL] 写入超时" : "普通错误"));
        vo.setDeadLetter(dead);
        vo.setSyncFail(fail);
        return vo;
    }

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("注入失败: " + field, e);
        }
    }

    private static final class StubHealthService implements ReplicationHealthService {
        final List<DeadLetterVO> rows = new ArrayList<DeadLetterVO>();

        @Override
        public org.dbdatasync.biz.model.ReplicationHealthReport queryHealth() {
            return new org.dbdatasync.biz.model.ReplicationHealthReport();
        }

        @Override
        public Paging<DeadLetterVO> queryDeadLetters(Map<String, String> params) {
            Paging<DeadLetterVO> page = new Paging<DeadLetterVO>(1, rows.size());
            page.setData((Collection) rows);
            page.setTotal(rows.size());
            return page;
        }

        @Override
        public int evaluateAndAlarm() {
            return 0;
        }
    }

    private static final class StubDataSyncService implements DataSyncService {
        final List<Map<String, String>> syncCalls = new ArrayList<Map<String, String>>();
        RuntimeException failOnNext;

        @Override
        public org.dbdatasync.biz.vo.MessageVO getMessageVo(String metaId, String messageId) {
            return null;
        }

        @Override
        public Map getBinlogData(Map row, boolean prettyBytes) {
            return null;
        }

        @Override
        public String sync(Map<String, String> params) throws com.google.protobuf.InvalidProtocolBufferException {
            syncCalls.add(params);
            if (failOnNext != null) {
                throw failOnNext;
            }
            return "ok";
        }

        @Override
        public void syncBatch(org.dbdatasync.biz.model.DataSyncRequest request) {
            // 不涉及
        }
    }

    private static final class StubConfigService implements SystemConfigService {
        @Override
        public SystemConfig getSystemConfig() {
            return null;
        }

        @Override
        public String edit(Map<String, String> params) {
            return null;
        }

        @Override
        public String editNoticeConfig(Map<String, String> params) {
            return null;
        }

        @Override
        public org.dbdatasync.biz.vo.SystemConfigVO getSystemConfigVo() {
            return null;
        }

        @Override
        public java.util.List<org.dbdatasync.common.model.ConfigModel> getConfigModelAll() {
            return null;
        }

        @Override
        public void checkFileSuffix(String filename) {
            // 不涉及
        }

        @Override
        public void refreshConfig(java.io.File file) {
            // 不涉及
        }

        @Override
        public long estimateExportSize() {
            return 0;
        }

        @Override
        public String getWatermark(SystemConfig systemConfig) {
            return null;
        }

        @Override
        public org.dbdatasync.common.model.RsaVersion createRSAConfig(int keyLength) {
            return null;
        }

        @Override
        public String generateApiSecret() {
            return null;
        }
    }
}
