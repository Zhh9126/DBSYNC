/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.retry;

import org.junit.Test;

import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 写入失败分类 / 退避 / 死信标签的离线单测（不连库、不启服务）。
 *
 * @author wuji
 * @version 1.0.0
 */
public class WriteRetryPolicyTest {

    private static RuntimeException ex(String message) {
        return new RuntimeException(message);
    }

    @Test
    public void classifyTransientByMessage() {
        assertEquals(WriteRetryPolicy.FailureKind.TRANSIENT,
                WriteRetryPolicy.classify(ex("Deadlock found when trying to get lock")));
        assertEquals(WriteRetryPolicy.FailureKind.TRANSIENT,
                WriteRetryPolicy.classify(ex("Communications link failure, connection reset by peer")));
        assertEquals(WriteRetryPolicy.FailureKind.TRANSIENT,
                WriteRetryPolicy.classify(ex("ORA-00060: deadlock detected while waiting for resource")));
        assertTrue(WriteRetryPolicy.isRetryable(ex("Lock wait timeout exceeded")));
    }

    @Test
    public void classifyPermanentByMessage() {
        assertEquals(WriteRetryPolicy.FailureKind.PERMANENT,
                WriteRetryPolicy.classify(ex("Duplicate entry '1' for key 'PRIMARY'")));
        assertEquals(WriteRetryPolicy.FailureKind.PERMANENT,
                WriteRetryPolicy.classify(ex("Unknown column 'abc' in 'field list'")));
        assertEquals(WriteRetryPolicy.FailureKind.PERMANENT,
                WriteRetryPolicy.classify(ex("ORA-00942: table or view does not exist")));
        assertFalse(WriteRetryPolicy.isRetryable(ex("Data truncation: data too long for column")));
    }

    @Test
    public void classifyUnknownIsConservative() {
        assertEquals(WriteRetryPolicy.FailureKind.UNKNOWN,
                WriteRetryPolicy.classify(ex("数据不存在或执行异常")));
        assertFalse(WriteRetryPolicy.isRetryable(ex("数据不存在或执行异常")));
        assertEquals(WriteRetryPolicy.FailureKind.UNKNOWN, WriteRetryPolicy.classify(null));
    }

    @Test
    public void classifyWalksCauseChain() {
        RuntimeException nested = new RuntimeException("wrapper",
                new IllegalStateException("connection reset by peer"));
        assertEquals(WriteRetryPolicy.FailureKind.TRANSIENT, WriteRetryPolicy.classify(nested));
    }

    @Test
    public void permanentWinsOverTransient() {
        RuntimeException mixed = new RuntimeException("deadlock detected",
                new IllegalArgumentException("duplicate key value violates unique constraint"));
        assertEquals(WriteRetryPolicy.FailureKind.PERMANENT, WriteRetryPolicy.classify(mixed));
    }

    @Test
    public void classifyBySqlState() {
        SQLException e = new SQLException("weird reason", "08006", 0);
        assertEquals(WriteRetryPolicy.FailureKind.TRANSIENT, WriteRetryPolicy.classify(e));
        SQLException deadlock = new SQLException("transaction rollback", "40001", 0);
        assertEquals(WriteRetryPolicy.FailureKind.TRANSIENT, WriteRetryPolicy.classify(deadlock));
    }

    @Test
    public void backoffIsExponentialCappedAndJittered() {
        assertEquals(25L, WriteRetryPolicy.backoffMillis(1, 50L, 500L, 0d));
        assertEquals(50L, WriteRetryPolicy.backoffMillis(1, 50L, 500L, 1d));
        assertEquals(50L, WriteRetryPolicy.backoffMillis(2, 50L, 500L, 0d));
        assertEquals(100L, WriteRetryPolicy.backoffMillis(2, 50L, 500L, 1d));
        // cap 生效，且不会因指数放大溢出
        assertEquals(250L, WriteRetryPolicy.backoffMillis(20, 50L, 500L, 0d));
        assertTrue(WriteRetryPolicy.backoffMillis(20, 50L, 500L, 0d) <= 500L);
        assertEquals(0L, WriteRetryPolicy.backoffMillis(0, 50L, 500L, 1d));
        assertEquals(0L, WriteRetryPolicy.backoffMillis(1, 0L, 500L, 1d));
    }

    @Test
    public void shouldRetryRespectsKindAttemptsAndBudget() {
        WriteRetryPolicy.FailureKind transientKind = WriteRetryPolicy.FailureKind.TRANSIENT;
        assertTrue(WriteRetryPolicy.shouldRetry(transientKind, 1, 3, 100L, 2000L));
        assertFalse(WriteRetryPolicy.shouldRetry(transientKind, 3, 3, 100L, 2000L));
        assertFalse(WriteRetryPolicy.shouldRetry(transientKind, 1, 3, 2000L, 2000L));
        assertFalse(WriteRetryPolicy.shouldRetry(WriteRetryPolicy.FailureKind.PERMANENT, 1, 3, 0L, 2000L));
        assertFalse(WriteRetryPolicy.shouldRetry(WriteRetryPolicy.FailureKind.UNKNOWN, 1, 3, 0L, 2000L));
    }

    @Test
    public void settingsConvenienceOverload() {
        WriteRetrySettings on = WriteRetrySettings.defaults();
        WriteRetrySettings off = WriteRetrySettings.disabled();
        assertTrue(WriteRetryPolicy.shouldRetry(ex("connection reset"), 1, on, 0L));
        assertFalse(WriteRetryPolicy.shouldRetry(ex("connection reset"), 1, off, 0L));
        assertTrue(on.isEnabled());
        assertFalse(off.isEnabled());
    }

    @Test
    public void failureTagPermanentBecomesDeadLetter() {
        String error = "SQL: insert into t" + System.lineSeparator()
                + "ERROR: Duplicate entry '1' for key 'PRIMARY'" + System.lineSeparator()
                + WriteFailureTag.describe(ex("Duplicate entry '1' for key 'PRIMARY'"));
        String tagged = WriteFailureTag.tag(error, 3);
        assertTrue(WriteFailureTag.isDeadLetter(tagged));
        assertEquals("PERMANENT", WriteFailureTag.kindOf(tagged));
        assertTrue(tagged.contains("[retry=3]"));
        // 重复打标幂等
        assertEquals(tagged, WriteFailureTag.tag(tagged, 9));
    }

    @Test
    public void mixedBatchErrorPrefersPermanentAndCleansAllTags() {
        String mixed = "ERROR: connection reset [kind=TRANSIENT] "
                + "ERROR: duplicate key [kind=PERMANENT]";
        assertEquals("PERMANENT", WriteFailureTag.kindOf(mixed));
        String tagged = WriteFailureTag.tag(mixed, 3);
        assertTrue(WriteFailureTag.isDeadLetter(tagged));
        assertFalse(tagged.contains("[kind=TRANSIENT]"));
        assertFalse(tagged.contains("[kind=PERMANENT][kind=PERMANENT]"));
        assertEquals(1, countOccurrences(tagged, WriteFailureTag.DEAD_LETTER));
    }

    @Test
    public void failureTagTransientStaysRetryableAndDoesNotStack() {
        String error = "ERROR: connection reset" + WriteFailureTag.describe(ex("connection reset"));
        String first = WriteFailureTag.tag(error, 1);
        assertTrue(WriteFailureTag.isSyncFail(first));
        assertFalse(WriteFailureTag.isDeadLetter(first));
        assertEquals("TRANSIENT", WriteFailureTag.kindOf(first));
        String second = WriteFailureTag.tag(first, 2);
        assertTrue(second.startsWith(WriteFailureTag.SYNC_FAIL));
        assertEquals(1, countOccurrences(second, WriteFailureTag.SYNC_FAIL));
        assertEquals("UNKNOWN", WriteFailureTag.kindOf("plain error without tag"));
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
