/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.health;

import org.dbdatasync.sdk.observer.ReplicationEventObservers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 链路运行时指标聚合的离线单测（不连库、不启服务）。
 *
 * @author wuji
 * @version 1.0.0
 */
public class ReplicationHealthTrackerTest {

    private ReplicationHealthTracker tracker;

    @Before
    public void setUp() {
        ReplicationEventObservers.clear();
        tracker = new ReplicationHealthTracker();
        tracker.reset();
        tracker.init();
    }

    @After
    public void tearDown() {
        tracker.destroy();
        ReplicationEventObservers.clear();
    }

    @Test
    public void eventsAreCountedWithLatestTimestamp() {
        ReplicationEventObservers.notifyEvent("m1", "INSERT", 1000L, "bin.1:1");
        ReplicationEventObservers.notifyEvent("m1", "UPDATE", 3000L, "bin.1:2");
        ReplicationEventObservers.notifyEvent("m1", "DELETE", 2000L, "bin.1:3");
        ReplicationHealthTracker.Counters c = tracker.counters("m1");
        assertEquals(3L, c.events);
        assertEquals(3000L, c.lastEventTime);
        assertTrue(c.firstSeenTime > 0);
    }

    @Test
    public void retryRecoveredAndDeadLettersAreAggregated() {
        ReplicationEventObservers.notifyRetryRecovered("m1", 2);
        ReplicationEventObservers.notifyRetryRecovered("m1", 3);
        ReplicationEventObservers.notifyDeadLetter("m1", "PERMANENT", "duplicate key");
        ReplicationEventObservers.notifyDeadLetter("m1", "PERMANENT", "duplicate key");
        ReplicationEventObservers.notifyDeadLetter("m1", "UNKNOWN", "other");
        ReplicationHealthTracker.Counters c = tracker.counters("m1");
        assertEquals(5L, c.retryRecovered);
        assertEquals(3L, c.deadLetters);
        assertEquals(Long.valueOf(2L), c.deadLetterByKind.get("PERMANENT"));
        assertEquals(Long.valueOf(1L), c.deadLetterByKind.get("UNKNOWN"));
    }

    @Test
    public void unknownLinkReturnsEmptyCounters() {
        ReplicationHealthTracker.Counters c = tracker.counters("missing");
        assertEquals(0L, c.events);
        assertEquals(0L, c.deadLetters);
        assertTrue(c.deadLetterByKind.isEmpty());
    }

    @Test
    public void countersAreIsolatedPerLinkAndSnapshotIsImmutable() {
        ReplicationEventObservers.notifyEvent("m1", "INSERT", 1000L, null);
        ReplicationEventObservers.notifyDeadLetter("m2", "PERMANENT", "x");
        assertEquals(1L, tracker.counters("m1").events);
        assertEquals(0L, tracker.counters("m2").events);
        assertEquals(1L, tracker.counters("m2").deadLetters);
        assertTrue(tracker.allCounters().size() >= 2);
        try {
            tracker.counters("m2").deadLetterByKind.put("HACK", 1L);
        } catch (UnsupportedOperationException expected) {
            // 快照不可变
        }
    }

    @Test
    public void nullMetaIdAndNonPositiveRowsAreIgnored() {
        ReplicationEventObservers.notifyEvent(null, "INSERT", 1000L, null);
        ReplicationEventObservers.notifyRetryRecovered("m1", 0);
        ReplicationEventObservers.notifyDeadLetter(null, "PERMANENT", "x");
        assertEquals(1, ReplicationEventObservers.size());
        assertEquals(0L, tracker.counters("m1").retryRecovered);
    }
}
