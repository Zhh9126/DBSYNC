package org.dbdatasync.biz.qa;

import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.impl.DatabaseSyncTaskRunner;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.util.MigrationStageUtil;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Wave11 自动推进阶段 / 限速写入独立回归用例。
 *
 * <p>JUnit 4；反射调用 private 方法 + JDK 动态代理 MetaProfile 桩，不连库、不启服务、
 * 不写真实数据、不改动任何生产源码。覆盖：</p>
 * <ol>
 *   <li>开关关闭不推进</li>
 *   <li>开关开启但 success=false 不推进</li>
 *   <li>当前 schedule 不推进</li>
 *   <li>当前 full 推进为 increment</li>
 *   <li>当前 validate/switchover/increment 不推进</li>
 *   <li>异常被吞，不影响收尾</li>
 *   <li>meta / snapshot 为 null 不抛异常且不推进</li>
 *   <li>buildStubMapping 仅在限速值 &gt;0 时写 params，且不写分片键</li>
 * </ol>
 *
 * @author impl-shared
 * @version 1.0.0
 * @date 2026-11-06
 */
public class Wave11StageAdvanceTest {

    @Test
    public void autoAdvanceDisabled_neverAdvance() throws Exception {
        Meta meta = metaWithStage("full");
        StubMetaProfile stub = callMaybeAutoAdvanceStage(newTask(false), true, new StubMetaProfile(meta));
        assertEquals(0, stub.updateCalls);
        assertEquals("full", stageOf(meta));
    }

    @Test
    public void successFalse_neverAdvance() throws Exception {
        Meta meta = metaWithStage("full");
        StubMetaProfile stub = callMaybeAutoAdvanceStage(newTask(true), false, new StubMetaProfile(meta));
        assertEquals(0, stub.updateCalls);
        assertEquals("full", stageOf(meta));
    }

    @Test
    public void currentSchedule_neverAdvance() throws Exception {
        Meta meta = metaWithStage("schedule");
        StubMetaProfile stub = callMaybeAutoAdvanceStage(newTask(true), true, new StubMetaProfile(meta));
        assertEquals(0, stub.updateCalls);
        assertEquals("schedule", stageOf(meta));
    }

    @Test
    public void currentFull_advanceToIncrement() throws Exception {
        Meta meta = metaWithStage("full");
        StubMetaProfile stub = callMaybeAutoAdvanceStage(newTask(true), true, new StubMetaProfile(meta));
        assertEquals(1, stub.updateCalls);
        assertEquals("increment", stageOf(meta));
    }

    @Test
    public void currentValidateSwitchoverIncrement_neverAdvance() throws Exception {
        for (String stage : new String[]{"validate", "switchover", "increment"}) {
            Meta meta = metaWithStage(stage);
            StubMetaProfile stub = callMaybeAutoAdvanceStage(newTask(true), true, new StubMetaProfile(meta));
            assertEquals(0, stub.updateCalls);
            assertEquals(stage, stageOf(meta));
        }
    }

    @Test
    public void exceptionSwallowed_noThrowAndNoAdvance() throws Exception {
        // 桩在 getMetaByTaskId 抛异常： Runner 内部必须吞掉，调用方不感知、updateMeta 不被调用
        StubMetaProfile stub = callMaybeAutoAdvanceStage(
                newTask(true), true, new StubMetaProfile(null, true));
        assertEquals(0, stub.updateCalls);
        // 能走到此处即证明异常被吞、收尾流程不受影响
    }

    @Test
    public void nullMetaOrNullSnapshot_noThrowAndNoAdvance() throws Exception {
        StubMetaProfile s1 = callMaybeAutoAdvanceStage(newTask(true), true, new StubMetaProfile(null));
        assertEquals(0, s1.updateCalls);

        Meta meta = new Meta();
        meta.setSnapshot(null);
        StubMetaProfile s2 = callMaybeAutoAdvanceStage(newTask(true), true, new StubMetaProfile(meta));
        assertEquals(0, s2.updateCalls);
        assertNotNull(meta.getSnapshot());
        assertNull(stageOf(meta));
    }

    @Test
    public void buildStubMapping_writesRateParamsOnlyWhenPositive() throws Exception {
        DatabaseSyncTaskRunner runner = new DatabaseSyncTaskRunner();
        Method method = DatabaseSyncTaskRunner.class
                .getDeclaredMethod("buildStubMapping", DatabaseSyncTask.class, TableGroup.class);
        method.setAccessible(true);

        // 全 0：不写任何限速键，也不写分片键
        DatabaseSyncTask t0 = new DatabaseSyncTask();
        t0.setId("wave11-rate-0");
        Mapping m0 = (Mapping) method.invoke(runner, t0, new TableGroup());
        assertFalse(m0.getParams().containsKey(ConfigConstant.RATE_RECORDS_PER_SECOND));
        assertFalse(m0.getParams().containsKey(ConfigConstant.RATE_BYTES_PER_SECOND));
        assertFalse(m0.getParams().containsKey(ConfigConstant.FULL_SPLIT_KEY));
        assertFalse(m0.getParams().containsKey(ConfigConstant.FULL_SPLIT_COUNT));

        // 仅 recordsPerSecond > 0：只写该键，bytesPerSecond 仍不写
        DatabaseSyncTask t1 = new DatabaseSyncTask();
        t1.setId("wave11-rate-1");
        t1.setRecordsPerSecond(1000L);
        t1.setBytesPerSecond(0L);
        Mapping m1 = (Mapping) method.invoke(runner, t1, new TableGroup());
        assertEquals("1000", m1.getParams().get(ConfigConstant.RATE_RECORDS_PER_SECOND));
        assertFalse(m1.getParams().containsKey(ConfigConstant.RATE_BYTES_PER_SECOND));
        assertFalse(m1.getParams().containsKey(ConfigConstant.FULL_SPLIT_KEY));
        assertFalse(m1.getParams().containsKey(ConfigConstant.FULL_SPLIT_COUNT));

        // 两者均 > 0
        DatabaseSyncTask t2 = new DatabaseSyncTask();
        t2.setId("wave11-rate-2");
        t2.setRecordsPerSecond(500L);
        t2.setBytesPerSecond(2048L);
        Mapping m2 = (Mapping) method.invoke(runner, t2, new TableGroup());
        assertEquals("500", m2.getParams().get(ConfigConstant.RATE_RECORDS_PER_SECOND));
        assertEquals("2048", m2.getParams().get(ConfigConstant.RATE_BYTES_PER_SECOND));
    }

    // ==================== 桩 / 工具 ====================

    private static DatabaseSyncTask newTask(boolean autoAdvance) {
        DatabaseSyncTask task = new DatabaseSyncTask();
        task.setId("wave11-task");
        task.setAutoAdvanceStage(autoAdvance);
        return task;
    }

    private static Meta metaWithStage(String stage) {
        Meta meta = new Meta();
        Map<String, String> snapshot = new HashMap<>();
        if (stage != null) {
            snapshot.put(MigrationStageUtil.SNAPSHOT_KEY_STAGE, stage);
        }
        meta.setSnapshot(snapshot);
        return meta;
    }

    private static String stageOf(Meta meta) {
        return meta.getSnapshot() == null
                ? null : meta.getSnapshot().get(MigrationStageUtil.SNAPSHOT_KEY_STAGE);
    }

    private static StubMetaProfile callMaybeAutoAdvanceStage(
            DatabaseSyncTask task, boolean success, StubMetaProfile stub) throws Exception {
        DatabaseSyncTaskRunner runner = new DatabaseSyncTaskRunner();
        MetaProfile proxy = (MetaProfile) Proxy.newProxyInstance(
                Wave11StageAdvanceTest.class.getClassLoader(),
                new Class<?>[]{MetaProfile.class}, stub);
        Field field = DatabaseSyncTaskRunner.class.getDeclaredField("metaProfile");
        field.setAccessible(true);
        field.set(runner, proxy);

        Method method = DatabaseSyncTaskRunner.class
                .getDeclaredMethod("maybeAutoAdvanceStage", DatabaseSyncTask.class, boolean.class);
        method.setAccessible(true);
        try {
            method.invoke(runner, task, success);
        } catch (InvocationTargetException e) {
            // 契约要求「异常被吞、绝不影响收尾」：若向外抛则断言失败
            throw new IllegalStateException("maybeAutoAdvanceStage 不应向外抛异常", e.getCause());
        }
        return stub;
    }

    /** MetaProfile 动态代理桩：记录 updateMeta 调用次数，可选在 getMetaByTaskId 抛异常。 */
    private static final class StubMetaProfile implements InvocationHandler {
        private final Meta meta;
        private final boolean throwOnGet;
        int updateCalls = 0;

        StubMetaProfile(Meta meta) {
            this(meta, false);
        }

        StubMetaProfile(Meta meta, boolean throwOnGet) {
            this.meta = meta;
            this.throwOnGet = throwOnGet;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getMetaByTaskId".equals(name)) {
                if (throwOnGet) {
                    throw new IllegalStateException("模拟 MetaProfile 不可用");
                }
                return meta;
            }
            if ("updateMeta".equals(name)) {
                updateCalls++;
                return "ok";
            }
            if ("toString".equals(name)) {
                return "StubMetaProfile";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }
}
