/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.observer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link DatabaseExecuteObservers} 单元测试：注册/幂等/清除/通知/异常吞噬。
 */
public class DatabaseExecuteObserversTest {

    @Before
    public void setUp() {
        DatabaseExecuteObservers.clear();
    }

    @After
    public void tearDown() {
        DatabaseExecuteObservers.clear();
    }

    static final class RecordingObserver implements DatabaseExecuteObserver {
        final List<String> calls = new ArrayList<String>();

        @Override
        public void onExecuteFinished(String sql, String source, long executeMillis, long rows) {
            calls.add(sql + "|" + source + "|" + executeMillis + "|" + rows);
        }
    }

    @Test
    public void noObserverNotifyIsNoop() {
        DatabaseExecuteObservers.notifyExecuted("SELECT 1", "src", 1L, 0L);
        assertTrue(DatabaseExecuteObservers.size() == 0);
    }

    @Test
    public void registerNotifyAndUnregister() {
        RecordingObserver observer = new RecordingObserver();
        DatabaseExecuteObservers.register(observer);
        assertEquals(1, DatabaseExecuteObservers.size());
        DatabaseExecuteObservers.notifyExecuted("UPDATE t", "src-a", 500L, 3L);
        assertEquals(1, observer.calls.size());
        assertEquals("UPDATE t|src-a|500|3", observer.calls.get(0));
        assertTrue(DatabaseExecuteObservers.unregister(observer));
        assertEquals(0, DatabaseExecuteObservers.size());
        DatabaseExecuteObservers.notifyExecuted("UPDATE t", "src-a", 500L, 3L);
        assertEquals(1, observer.calls.size());
    }

    @Test
    public void registerDedupesSameInstance() {
        RecordingObserver observer = new RecordingObserver();
        DatabaseExecuteObservers.register(observer);
        DatabaseExecuteObservers.register(observer);
        assertEquals(1, DatabaseExecuteObservers.size());
    }

    @Test
    public void nullRegisterIgnored() {
        DatabaseExecuteObservers.register(null);
        assertEquals(0, DatabaseExecuteObservers.size());
    }

    @Test
    public void observerExceptionDoesNotBreakOthers() {
        final AtomicInteger goodCalls = new AtomicInteger();
        DatabaseExecuteObservers.register(new DatabaseExecuteObserver() {
            @Override
            public void onExecuteFinished(String sql, String source, long executeMillis, long rows) {
                throw new IllegalStateException("observer boom");
            }
        });
        DatabaseExecuteObservers.register(new DatabaseExecuteObserver() {
            @Override
            public void onExecuteFinished(String sql, String source, long executeMillis, long rows) {
                goodCalls.incrementAndGet();
            }
        });
        // 首个观察者异常被吞噬，第二个观察者仍被通知
        DatabaseExecuteObservers.notifyExecuted("SELECT 1", "src", 1L, 0L);
        assertEquals(1, goodCalls.get());
        assertFalse(DatabaseExecuteObservers.unregister(new RecordingObserver()));
    }
}
