/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.util;

import java.time.LocalTime;
import java.util.regex.Pattern;

/**
 * 低峰时间窗工具：HH:mm 校验、跨午夜窗口判定、一次性 cron 生成与时间归一。
 *
 * <p>纯静态计算，无 Spring / 无 IO，便于单元测试。窗口语义：</p>
 * <ul>
 *     <li>严格 {@code HH:mm}（24 小时制），如 {@code 22:30}；</li>
 *     <li>{@code start < end}：同日窗口 {@code [start, end)}；</li>
 *     <li>{@code start > end}：跨午夜窗口（如 22:00~06:00）；</li>
 *     <li>{@code start == end}：全天窗口，恒为窗口内；</li>
 *     <li>非法配置一律「不阻断」（返回 true / null / ""），存量任务零影响。</li>
 * </ul>
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-11-06
 */
public final class OffPeakWindowUtil {

    /** 严格 HH:mm：00:00 ~ 23:59 */
    private static final Pattern HHMM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private OffPeakWindowUtil() {
    }

    /** 是否为合法 HH:mm（null / 空白 / 带空格 / 非 24 小时制一律 false）。 */
    public static boolean isValidTime(String hhmm) {
        return hhmm != null && HHMM.matcher(hhmm).matches();
    }

    /** 窗口是否可用：起止时间均为合法 HH:mm。 */
    public static boolean isConfigured(String start, String end) {
        return isValidTime(start) && isValidTime(end);
    }

    /**
     * 当前时间是否落在窗口内。非法配置（含 null 当前时间）不阻断，返回 true。
     * 同日窗口取 [start, end)；跨午夜（start &gt; end）取 now &gt;= start 或 now &lt; end。
     */
    public static boolean isWithin(LocalTime now, String start, String end) {
        if (!isConfigured(start, end)) {
            return true;
        }
        if (start.equals(end)) {
            return true;
        }
        if (now == null) {
            return true;
        }
        LocalTime s = LocalTime.parse(start);
        LocalTime e = LocalTime.parse(end);
        if (s.isBefore(e)) {
            return !now.isBefore(s) && now.isBefore(e);
        }
        return !now.isBefore(s) || now.isBefore(e);
    }

    /** 合法 HH:mm → 6 位 cron {@code 0 <mm> <HH> * * ?}（去掉前导 0）；非法返回 null。 */
    public static String toCron(String start) {
        if (!isValidTime(start)) {
            return null;
        }
        int hh = Integer.parseInt(start.substring(0, 2));
        int mm = Integer.parseInt(start.substring(3, 5));
        return "0 " + mm + " " + hh + " * * ?";
    }

    /** trim 后合法则原样返回，否则返回 ""。 */
    public static String normalizeTime(String hhmm) {
        if (hhmm == null) {
            return "";
        }
        String trimmed = hhmm.trim();
        return isValidTime(trimmed) ? trimmed : "";
    }
}
