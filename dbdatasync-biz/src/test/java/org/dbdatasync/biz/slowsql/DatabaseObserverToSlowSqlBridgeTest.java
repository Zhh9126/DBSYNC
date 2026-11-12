package org.dbdatasync.biz.slowsql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.dbdatasync.sdk.observer.DatabaseExecuteObserver;
import org.dbdatasync.sdk.observer.DatabaseExecuteObservers;
import org.dbdatasync.sdk.storage.TaskDetailContentStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link DatabaseObserverToSlowSqlBridge} 单元测试：验证注册/卸载、
 * 阈值过滤、来源标记、事件转发。不依赖真实数据库。
 */
public class DatabaseObserverToSlowSqlBridgeTest {

    private SlowSqlService slowSqlService;

    @Before
    public void setUp() {
        DatabaseExecuteObservers.clear();
        TaskDetailContentStore.uninstall();
        slowSqlService = new SlowSqlService();
    }

    @After
    public void tearDown() {
        DatabaseExecuteObservers.clear();
        TaskDetailContentStore.uninstall();
    }

    @Test
    public void registerAndUnregisterLifecycle() {
        DatabaseObserverToSlowSqlBridge bridge = new DatabaseObserverToSlowSqlBridge(slowSqlService);
        bridge.init();
        assertEquals(1, DatabaseExecuteObservers.size());
        bridge.destroy();
        assertEquals(0, DatabaseExecuteObservers.size());
    }

    @Test
    public void fastEventNotCollected() {
        DatabaseObserverToSlowSqlBridge bridge = new DatabaseObserverToSlowSqlBridge(slowSqlService);
        bridge.init();
        // 默认阈值 1000ms，1ms 事件不入采集
        bridge.onExecuteFinished("SELECT 1", "SimpleConnection", 1L, 0L);
        assertEquals(0, slowSqlService.records().size());
    }

    @Test
    public void slowEventCollectedWithSourceLabel() {
        DatabaseObserverToSlowSqlBridge bridge = new DatabaseObserverToSlowSqlBridge(slowSqlService);
        bridge.init();
        bridge.onExecuteFinished("UPDATE t SET c=1", "SimpleConnection", 2500L, 5L);
        assertEquals(1, slowSqlService.records().size());
        SlowSqlRecord record = slowSqlService.records().get(0);
        assertEquals("UPDATE t SET c=1", record.getSql());
        assertTrue(record.getSource().startsWith("observer:"));
        assertEquals(2500L, record.getExecuteMillis());
        assertEquals(5L, record.getRows());
        assertTrue(record.isThresholdExceeded());
    }

    @Test
    public void registerDedupesSameInstance() {
        DatabaseObserverToSlowSqlBridge bridge = new DatabaseObserverToSlowSqlBridge(slowSqlService);
        bridge.init();
        bridge.init();
        assertEquals(1, DatabaseExecuteObservers.size());
        bridge.destroy();
    }

    @Test
    public void observerExceptionSwallowedByRegistry() {
        final boolean[] called = new boolean[1];
        DatabaseExecuteObservers.register(new DatabaseExecuteObserver() {
            @Override
            public void onExecuteFinished(String sql, String source, long executeMillis, long rows) {
                called[0] = true;
                throw new IllegalStateException("boom");
            }
        });
        // 异常被吞噬，不向上抛
        DatabaseExecuteObservers.notifyExecuted("SELECT 1", "src", 10L, 0L);
        assertTrue(called[0]);
        assertEquals(1, DatabaseExecuteObservers.size());
    }
}
