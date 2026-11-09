/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.health;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 链路健康分级的纯函数单测（不连库、不启服务）。
 *
 * @author wuji
 * @version 1.0.0
 */
public class HealthEvaluatorTest {

    private static HealthEvaluator.Input input(boolean running, boolean hasEvent, long stall,
                                               double failRate, long dead) {
        return new HealthEvaluator.Input(running, hasEvent, stall, failRate, dead, 60L, 300L, 5d);
    }

    @Test
    public void idleLinksDoNotPenalizeScore() {
        HealthEvaluator.Output out = HealthEvaluator.evaluate(input(false, false, 9999L, 50d, 30L));
        assertEquals(HealthLevel.IDLE, out.level);
        assertEquals(100, out.score);
    }

    @Test
    public void freshHealthyLink() {
        HealthEvaluator.Output out = HealthEvaluator.evaluate(input(true, true, 3L, 0d, 0L));
        assertEquals(HealthLevel.HEALTHY, out.level);
        assertEquals(100, out.score);
        assertEquals("链路正常", out.reason);
    }

    @Test
    public void warnOnStallThreshold() {
        HealthEvaluator.Output out = HealthEvaluator.evaluate(input(true, true, 90L, 0d, 0L));
        assertEquals(HealthLevel.WARN, out.level);
        assertEquals(60, out.score);
        assertTrue(out.reason.contains("预警阈值"));
    }

    @Test
    public void staleOnCriticalThreshold() {
        HealthEvaluator.Output out = HealthEvaluator.evaluate(input(true, true, 400L, 0d, 0L));
        assertEquals(HealthLevel.STALE, out.level);
        assertEquals(20, out.score);
        assertTrue(out.reason.contains("严重阈值"));
    }

    @Test
    public void noEventStallIsMarkedAsPossibleIdleSource() {
        HealthEvaluator.Output out = HealthEvaluator.evaluate(input(true, false, 400L, 0d, 0L));
        assertEquals(HealthLevel.STALE, out.level);
        assertTrue(out.reason.contains("源端空闲"));
    }

    @Test
    public void failRateWarnAndCritical() {
        HealthEvaluator.Output warn = HealthEvaluator.evaluate(input(true, true, 1L, 6d, 0L));
        assertEquals(HealthLevel.WARN, warn.level);
        assertEquals(50, warn.score);
        HealthEvaluator.Output critical = HealthEvaluator.evaluate(input(true, true, 1L, 12d, 0L));
        assertEquals(HealthLevel.CRITICAL, critical.level);
        assertEquals(30, critical.score);
    }

    @Test
    public void deadLettersRaiseLevelAndReduceScoreWithCap() {
        HealthEvaluator.Output out = HealthEvaluator.evaluate(input(true, true, 1L, 0d, 3L));
        assertEquals(HealthLevel.WARN, out.level);
        assertEquals(94, out.score);
        assertTrue(out.reason.contains("死信 3 条"));
        HealthEvaluator.Output many = HealthEvaluator.evaluate(input(true, true, 1L, 0d, 1000L));
        assertEquals(80, many.score);
    }

    @Test
    public void multipleIssuesTakeWorstLevelAndLowestScore() {
        HealthEvaluator.Output out = HealthEvaluator.evaluate(input(true, true, 500L, 20d, 5L));
        assertEquals(HealthLevel.STALE, out.level);
        assertTrue(out.score <= 10);
        assertTrue(out.reason.contains("链路停滞"));
        assertTrue(out.reason.contains("失败率"));
        assertTrue(out.reason.contains("死信"));
    }

    @Test
    public void disabledThresholdsUseSafeFallback() {
        HealthEvaluator.Output out = HealthEvaluator.evaluate(
                new HealthEvaluator.Input(true, true, 10L, 0d, 0L, 0L, 0L, 0d));
        assertEquals(HealthLevel.HEALTHY, out.level);
        assertEquals(100, out.score);
    }

    @Test
    public void negativeValuesAreNormalized() {
        HealthEvaluator.Output out = HealthEvaluator.evaluate(input(true, true, -100L, -5d, -9L));
        assertEquals(HealthLevel.HEALTHY, out.level);
        assertEquals(100, out.score);
    }

    @Test
    public void safeValueOfNeverThrows() {
        assertEquals(HealthLevel.IDLE, HealthLevel.safeValueOf(null));
        assertEquals(HealthLevel.IDLE, HealthLevel.safeValueOf("NOT_A_LEVEL"));
        assertEquals(HealthLevel.STALE, HealthLevel.safeValueOf("STALE"));
        assertTrue(HealthLevel.STALE.getSeverity() > HealthLevel.CRITICAL.getSeverity());
    }
}
