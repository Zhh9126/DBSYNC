/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.plugin.model;

import org.dbdatasync.sdk.enums.NoticeTypeEnum;
import org.dbdatasync.sdk.model.NoticeContent;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时链路健康告警内容。
 *
 * <p>对标专业同步工具的 lag / SLO 告警：一条消息汇总多条异常链路，
 * 每条附带停滞时长、失败率、死信数与当前等级，便于运维直接定位。
 *
 * @author wuji
 * @version 1.0.0
 */
public final class ReplicationHealthContent extends NoticeContent {

    private List<ErrorItem> errorItems;

    public ReplicationHealthContent() {
        setNoticeType(NoticeTypeEnum.REPLICATION_HEALTH);
    }

    public void addErrorItem(ErrorItem item) {
        if (errorItems == null) {
            errorItems = new ArrayList<>();
        }
        errorItems.add(item);
    }

    public List<ErrorItem> getErrorItems() {
        return errorItems;
    }

    public static class ErrorItem {
        private String name;
        private String metaId;
        private String level;
        private long stallSeconds;
        private long thresholdSeconds;
        private double failRate;
        private long deadLetters;
        private String reason;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getMetaId() {
            return metaId;
        }

        public void setMetaId(String metaId) {
            this.metaId = metaId;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public long getStallSeconds() {
            return stallSeconds;
        }

        public void setStallSeconds(long stallSeconds) {
            this.stallSeconds = stallSeconds;
        }

        public long getThresholdSeconds() {
            return thresholdSeconds;
        }

        public void setThresholdSeconds(long thresholdSeconds) {
            this.thresholdSeconds = thresholdSeconds;
        }

        public double getFailRate() {
            return failRate;
        }

        public void setFailRate(double failRate) {
            this.failRate = failRate;
        }

        public long getDeadLetters() {
            return deadLetters;
        }

        public void setDeadLetters(long deadLetters) {
            this.deadLetters = deadLetters;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
