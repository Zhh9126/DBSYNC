/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.health;

import java.util.ArrayList;
import java.util.List;

/**
 * 链路健康分级（纯函数、零依赖，便于离线单测与回归）。
 *
 * <p>判定维度：任务运行态、链路停滞时长、累计失败率、死信堆积。
 * 多个维度同时异常时取最严重等级、取最低健康分，原因逐条拼接，便于运维直接读懂。
 *
 * @author wuji
 * @version 1.0.0
 */
public final class HealthEvaluator {

    /** 单条死信扣分上限 */
    public static final int DEAD_LETTER_PENALTY_PER_ITEM = 2;
    public static final int DEAD_LETTER_PENALTY_MAX = 20;

    public static final class Input {
        public final boolean running;
        public final boolean hasEvent;
        public final long stallSeconds;
        public final double failRate;
        public final long deadLetters;
        public final long warnSeconds;
        public final long criticalSeconds;
        public final double failRateWarnPercent;
        /** Wave23：真实端到端复制延迟（秒）；仅 lagIsReal=true 时用于阈值判定 */
        public final long lagSeconds;
        public final boolean lagIsReal;

        public Input(boolean running, boolean hasEvent, long stallSeconds, double failRate,
                     long deadLetters, long warnSeconds, long criticalSeconds, double failRateWarnPercent) {
            this(running, hasEvent, stallSeconds, failRate, deadLetters, warnSeconds,
                    criticalSeconds, failRateWarnPercent, 0L, false);
        }

        public Input(boolean running, boolean hasEvent, long stallSeconds, double failRate,
                     long deadLetters, long warnSeconds, long criticalSeconds, double failRateWarnPercent,
                     long lagSeconds, boolean lagIsReal) {
            this.running = running;
            this.hasEvent = hasEvent;
            this.stallSeconds = stallSeconds < 0 ? 0 : stallSeconds;
            this.failRate = failRate < 0 ? 0 : failRate;
            this.deadLetters = deadLetters < 0 ? 0 : deadLetters;
            this.warnSeconds = warnSeconds;
            this.criticalSeconds = criticalSeconds;
            this.failRateWarnPercent = failRateWarnPercent;
            this.lagSeconds = lagSeconds < 0 ? 0 : lagSeconds;
            this.lagIsReal = lagIsReal;
        }
    }

    public static final class Output {
        public final HealthLevel level;
        public final int score;
        public final String reason;

        public Output(HealthLevel level, int score, String reason) {
            this.level = level;
            this.score = score < 0 ? 0 : (score > 100 ? 100 : score);
            this.reason = reason;
        }
    }

    private HealthEvaluator() {
    }

    public static Output evaluate(Input in) {
        if (in == null || !in.running) {
            return new Output(HealthLevel.IDLE, 100, "任务未运行");
        }
        HealthLevel level = HealthLevel.HEALTHY;
        int score = 100;
        List<String> reasons = new ArrayList<>();

        long critical = in.criticalSeconds > 0 ? in.criticalSeconds : Long.MAX_VALUE;
        long warn = in.warnSeconds > 0 ? in.warnSeconds : critical;
        // 有源端事件时间时以「端到端复制延迟」为准，否则退回「距最后事件的停滞时长」
        long measured = in.lagIsReal ? in.lagSeconds : in.stallSeconds;
        String metricName = in.lagIsReal ? "端到端复制延迟" : "链路停滞";
        if (measured >= critical) {
            level = raise(level, HealthLevel.STALE);
            score = Math.min(score, 20);
            reasons.add(metricName + " " + measured + "s，超过严重阈值 " + critical + "s"
                    + (in.hasEvent ? "" : "（尚无事件，可能是源端空闲）"));
        } else if (measured >= warn) {
            level = raise(level, HealthLevel.WARN);
            score = Math.min(score, 60);
            reasons.add(metricName + " " + measured + "s，超过预警阈值 " + warn + "s");
        }

        double warnPercent = in.failRateWarnPercent > 0 ? in.failRateWarnPercent : 5d;
        if (in.failRate >= warnPercent * 2 && in.failRate > 0) {
            level = raise(level, HealthLevel.CRITICAL);
            score = Math.min(score, 30);
            reasons.add(String.format("累计失败率 %.2f%%，超过严重阈值 %.2f%%", in.failRate, warnPercent * 2));
        } else if (in.failRate >= warnPercent && in.failRate > 0) {
            level = raise(level, HealthLevel.WARN);
            score = Math.min(score, 50);
            reasons.add(String.format("累计失败率 %.2f%%，超过预警阈值 %.2f%%", in.failRate, warnPercent));
        }

        if (in.deadLetters > 0) {
            level = raise(level, HealthLevel.WARN);
            int penalty = (int) Math.min(DEAD_LETTER_PENALTY_MAX, in.deadLetters * DEAD_LETTER_PENALTY_PER_ITEM);
            score = Math.max(0, score - penalty);
            reasons.add("死信 " + in.deadLetters + " 条待人工处理");
        }

        if (reasons.isEmpty()) {
            return new Output(HealthLevel.HEALTHY, 100, "链路正常");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) {
                sb.append("；");
            }
            sb.append(reasons.get(i));
        }
        return new Output(level, score, sb.toString());
    }

    private static HealthLevel raise(HealthLevel current, HealthLevel candidate) {
        return candidate.getSeverity() > current.getSeverity() ? candidate : current;
    }
}
