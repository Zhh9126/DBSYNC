/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.health;

/**
 * 实时链路健康等级。
 *
 * @author wuji
 * @version 1.0.0
 */
public enum HealthLevel {

    /** 任务未运行，不参与健康分扣减 */
    IDLE("未运行", 0),
    /** 链路正常 */
    HEALTHY("健康", 1),
    /** 需要关注：停滞超预警阈值 / 失败率偏高 / 存在死信 */
    WARN("预警", 2),
    /** 严重：失败率远超阈值 */
    CRITICAL("严重", 3),
    /** 链路停滞（超过严重阈值），最高优先级 */
    STALE("停滞", 4);

    private final String label;
    private final int severity;

    HealthLevel(String label, int severity) {
        this.label = label;
        this.severity = severity;
    }

    public String getLabel() {
        return label;
    }

    public int getSeverity() {
        return severity;
    }

    public static HealthLevel safeValueOf(String name) {
        if (name == null) {
            return IDLE;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return IDLE;
        }
    }
}
