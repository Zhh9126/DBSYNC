/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.observer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * 观察点注册中心的幂等性与异常隔离（不连库、不启服务）。
 *
 * @author wuji
 * @version 1.0.0
 */
public class ReplicationEventObserversTest {

    private static final class CountingObserver implements ReplicationEventObserver {
        final AtomicInteger events = new AtomicInteger();
        final AtomicInteger recovered = new AtomicInteger();
        final AtomicInteger dead = new AtomicInteger();
        final boolean explode;

        CountingObserver(boolean explode) {
            this.explode = explode;
        }

        @Override
        public void onEvent(String metaId, String eventType, long receivedTimeMillis, String position) {
            if (explode) {
                throw new IllegalStateException("boom");
            }
            events.incrementAndGet();
        }

        @Override
        public void onRetryRecovered(String metaId, int rows) {
            if (explode) {
                throw new IllegalStateException("boom");
            }
            recovered.addAndGet(rows);
        }

        @Override
        public void onDeadLetter(String metaId, String kind, String cause) {
            if (explode) {
                throw new IllegalStateException("boom");
            }
            dead.incrementAndGet();
        }
    }

    @Before
    public void setUp() {
        ReplicationEventObservers.clear();
    }

    @After
    public void tearDown() {
        ReplicationEventObservers.clear();
    }

    @Test
    public void registerIsIdempotent() {
        CountingObserver observer = new CountingObserver(false);
        ReplicationEventObservers.register(observer);
        ReplicationEventObservers.register(observer);
        assertEquals(1, ReplicationEventObservers.size());
        ReplicationEventObservers.notifyEvent("m1", "INSERT", 1000L, "bin.0001:4");
        assertEquals(1, observer.events.get());
    }

    @Test
    public void observerExceptionDoesNotBreakOthersOrMainChain() {
        CountingObserver bad = new CountingObserver(true);
        CountingObserver good = new CountingObserver(false);
        ReplicationEventObservers.register(bad);
        ReplicationEventObservers.register(good);
        ReplicationEventObservers.notifyEvent("m1", "UPDATE", 1000L, null);
        ReplicationEventObservers.notifyRetryRecovered("m1", 3);
        ReplicationEventObservers.notifyDeadLetter("m1", "PERMANENT", "duplicate");
        assertEquals(1, good.events.get());
        assertEquals(3, good.recovered.get());
        assertEquals(1, good.dead.get());
    }

    @Test
    public void unregisterAndZeroRowsShortCircuit() {
        CountingObserver observer = new CountingObserver(false);
        ReplicationEventObservers.register(observer);
        ReplicationEventObservers.notifyRetryRecovered("m1", 0);
        assertEquals(0, observer.recovered.get());
        ReplicationEventObservers.unregister(observer);
        assertEquals(0, ReplicationEventObservers.size());
        ReplicationEventObservers.notifyEvent("m1", "INSERT", 1000L, null);
        assertEquals(0, observer.events.get());
    }

    @Test
    public void safePositionNeverThrows() {
        assertEquals(null, ReplicationEventObservers.safePosition(null));
        assertEquals("123", ReplicationEventObservers.safePosition(123L));
    }
}
