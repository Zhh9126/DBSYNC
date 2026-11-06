/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.util;

import org.junit.Test;

import java.time.LocalTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 低峰时间窗工具单元测试：合法/非法格式、跨午夜内外、全天窗口、非法配置不阻断、cron 生成、null/blank。
 */
public class OffPeakWindowUtilTest {

    @Test
    public void testIsValidTimeLegal() {
        assertTrue(OffPeakWindowUtil.isValidTime("00:00"));
        assertTrue(OffPeakWindowUtil.isValidTime("09:05"));
        assertTrue(OffPeakWindowUtil.isValidTime("22:30"));
        assertTrue(OffPeakWindowUtil.isValidTime("23:59"));
        assertTrue(OffPeakWindowUtil.isValidTime("19:59"));
    }

    @Test
    public void testIsValidTimeIllegal() {
        assertFalse(OffPeakWindowUtil.isValidTime("24:00"));
        assertFalse(OffPeakWindowUtil.isValidTime("23:60"));
        assertFalse(OffPeakWindowUtil.isValidTime("12:6"));
        assertFalse(OffPeakWindowUtil.isValidTime("1:30"));
        assertFalse(OffPeakWindowUtil.isValidTime("1230"));
        assertFalse(OffPeakWindowUtil.isValidTime("22:3a"));
        assertFalse(OffPeakWindowUtil.isValidTime(" 22:30 "));   // 严格格式，带空格不合法
        assertFalse(OffPeakWindowUtil.isValidTime("22：30"));     // 全角冒号
    }

    @Test
    public void testIsValidTimeNullAndBlank() {
        assertFalse(OffPeakWindowUtil.isValidTime(null));
        assertFalse(OffPeakWindowUtil.isValidTime(""));
        assertFalse(OffPeakWindowUtil.isValidTime("   "));
    }

    @Test
    public void testIsConfigured() {
        assertTrue(OffPeakWindowUtil.isConfigured("22:00", "06:00"));
        assertTrue(OffPeakWindowUtil.isConfigured("09:00", "18:00"));
        assertFalse(OffPeakWindowUtil.isConfigured("22:00", "bad"));
        assertFalse(OffPeakWindowUtil.isConfigured("", "06:00"));
        assertFalse(OffPeakWindowUtil.isConfigured(null, null));
    }

    @Test
    public void testNormalizeTime() {
        assertEquals("22:30", OffPeakWindowUtil.normalizeTime("22:30"));
        assertEquals("00:05", OffPeakWindowUtil.normalizeTime(" 00:05 "));
        assertEquals("", OffPeakWindowUtil.normalizeTime("bad"));
        assertEquals("", OffPeakWindowUtil.normalizeTime("24:00"));
        assertEquals("", OffPeakWindowUtil.normalizeTime("   "));
        assertEquals("", OffPeakWindowUtil.normalizeTime(null));
    }

    @Test
    public void testIsWithinSameDayWindow() {
        // 09:00 ~ 18:00（同日，含起点不含终点）
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(9, 0), "09:00", "18:00"));
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(12, 30), "09:00", "18:00"));
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(17, 59), "09:00", "18:00"));
        assertFalse(OffPeakWindowUtil.isWithin(LocalTime.of(8, 59), "09:00", "18:00"));
        assertFalse(OffPeakWindowUtil.isWithin(LocalTime.of(18, 0), "09:00", "18:00"));
        assertFalse(OffPeakWindowUtil.isWithin(LocalTime.of(23, 0), "09:00", "18:00"));
    }

    @Test
    public void testIsWithinCrossMidnight() {
        // 22:00 ~ 06:00（跨午夜）
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(22, 0), "22:00", "06:00"));
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(23, 59), "22:00", "06:00"));
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(0, 0), "22:00", "06:00"));
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(5, 59), "22:00", "06:00"));
        assertFalse(OffPeakWindowUtil.isWithin(LocalTime.of(21, 59), "22:00", "06:00"));
        assertFalse(OffPeakWindowUtil.isWithin(LocalTime.of(6, 0), "22:00", "06:00"));
        assertFalse(OffPeakWindowUtil.isWithin(LocalTime.of(12, 0), "22:00", "06:00"));
    }

    @Test
    public void testIsWithinAllDayAndInvalidConfig() {
        // start == end：全天窗口恒 true
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(3, 0), "08:00", "08:00"));
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(23, 59), "00:00", "00:00"));
        // 非法配置不阻断
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(12, 0), "bad", "06:00"));
        assertTrue(OffPeakWindowUtil.isWithin(LocalTime.of(12, 0), null, null));
        // 当前时间为 null 也不阻断
        assertTrue(OffPeakWindowUtil.isWithin(null, "09:00", "18:00"));
    }

    @Test
    public void testToCron() {
        assertEquals("0 5 0 * * ?", OffPeakWindowUtil.toCron("00:05"));
        assertEquals("0 30 22 * * ?", OffPeakWindowUtil.toCron("22:30"));
        assertEquals("0 0 9 * * ?", OffPeakWindowUtil.toCron("09:00"));
        assertEquals("0 59 23 * * ?", OffPeakWindowUtil.toCron("23:59"));
        assertNull(OffPeakWindowUtil.toCron("bad"));
        assertNull(OffPeakWindowUtil.toCron("24:00"));
        assertNull(OffPeakWindowUtil.toCron(null));
        assertNull(OffPeakWindowUtil.toCron(""));
    }
}
