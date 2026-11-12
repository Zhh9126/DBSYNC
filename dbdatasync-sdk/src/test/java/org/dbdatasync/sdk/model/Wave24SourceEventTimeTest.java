package org.dbdatasync.sdk.model;

import org.dbdatasync.sdk.listener.event.RowChangedEvent;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Wave24：各连接器真实源端事件时间的统一打点与换算。 */
public class Wave24SourceEventTimeTest {

    @Test
    public void pgEpochMicrosConvertToEpochMillis() {
        // 2000-01-01T00:00:00Z + 1 秒
        assertEquals(SourceEventTime.PG_EPOCH_MILLIS + 1000L,
                SourceEventTime.pgMicrosToEpochMillis(1_000_000L));
        assertEquals(0L, SourceEventTime.pgMicrosToEpochMillis(0L));
        assertEquals(946_684_800_000L, SourceEventTime.PG_EPOCH_MILLIS);
        assertEquals(1_700_000_000_000L, SourceEventTime.secondsToMillis(1_700_000_000L));
    }

    @Test
    public void applyMarksRealSourceTime() {
        RowChangedEvent event = new RowChangedEvent("t1", "insert", new ArrayList<Object>(), null, 11L);
        assertTrue(event.getChangedOffset().getEventTime() > 0);
        assertFalse(event.getChangedOffset().isSourceEventTime());

        SourceEventTime.apply(event, 1_700_000_001_234L, ChangedOffset.EVENT_TIME_SOURCE);

        assertTrue(event.getChangedOffset().isSourceEventTime());
        assertEquals(1_700_000_001_234L, event.getChangedOffset().getEventTime());
        assertEquals(ChangedOffset.EVENT_TIME_SOURCE, event.getChangedOffset().getEventTimeSource());
    }

    @Test
    public void zeroOrNullDoesNotFakeSourceTime() {
        RowChangedEvent event = new RowChangedEvent("t1", "insert", new ArrayList<Object>(), null, 12L);
        SourceEventTime.apply(event, 0L, ChangedOffset.EVENT_TIME_SOURCE);
        assertFalse(event.getChangedOffset().isSourceEventTime());
        SourceEventTime.apply(null, 1L, ChangedOffset.EVENT_TIME_SOURCE);
        SourceEventTime.apply(event, 1_700_000_000_000L, ChangedOffset.EVENT_TIME_ESTIMATED);
        assertFalse(event.getChangedOffset().isSourceEventTime());
        assertEquals(ChangedOffset.EVENT_TIME_ESTIMATED, event.getChangedOffset().getEventTimeSource());
    }
}
