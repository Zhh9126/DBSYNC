/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.model;

import org.dbdatasync.biz.vo.LinkHealthVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时链路健康总览。
 *
 * @author wuji
 * @version 1.0.0
 */
public class ReplicationHealthReport {

    private long generatedAt;

    private int lagWarnSeconds;
    private int lagCriticalSeconds;
    private int failRateWarnPercent;
    private boolean alarmEnabled;
    private boolean prometheusEnabled;

    private int totalLinks;
    private int runningLinks;
    private int healthyLinks;
    private int warnLinks;
    private int criticalLinks;
    private int staleLinks;
    private int idleLinks;
    private int healthScore;

    private long eventsTotal;
    private long retryRecoveredTotal;
    private long deadLetterTotal;
    private long maxStallSeconds;
    /** Wave23：最大真实端到端复制延迟（秒），0 表示当前无 source 时间链路 */
    private long maxLagSeconds;

    private List<LinkHealthVO> links = new ArrayList<>();

    public long getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(long generatedAt) {
        this.generatedAt = generatedAt;
    }

    public int getLagWarnSeconds() {
        return lagWarnSeconds;
    }

    public void setLagWarnSeconds(int lagWarnSeconds) {
        this.lagWarnSeconds = lagWarnSeconds;
    }

    public int getLagCriticalSeconds() {
        return lagCriticalSeconds;
    }

    public void setLagCriticalSeconds(int lagCriticalSeconds) {
        this.lagCriticalSeconds = lagCriticalSeconds;
    }

    public int getFailRateWarnPercent() {
        return failRateWarnPercent;
    }

    public void setFailRateWarnPercent(int failRateWarnPercent) {
        this.failRateWarnPercent = failRateWarnPercent;
    }

    public boolean isAlarmEnabled() {
        return alarmEnabled;
    }

    public void setAlarmEnabled(boolean alarmEnabled) {
        this.alarmEnabled = alarmEnabled;
    }

    public boolean isPrometheusEnabled() {
        return prometheusEnabled;
    }

    public void setPrometheusEnabled(boolean prometheusEnabled) {
        this.prometheusEnabled = prometheusEnabled;
    }

    public int getTotalLinks() {
        return totalLinks;
    }

    public void setTotalLinks(int totalLinks) {
        this.totalLinks = totalLinks;
    }

    public int getRunningLinks() {
        return runningLinks;
    }

    public void setRunningLinks(int runningLinks) {
        this.runningLinks = runningLinks;
    }

    public int getHealthyLinks() {
        return healthyLinks;
    }

    public void setHealthyLinks(int healthyLinks) {
        this.healthyLinks = healthyLinks;
    }

    public int getWarnLinks() {
        return warnLinks;
    }

    public void setWarnLinks(int warnLinks) {
        this.warnLinks = warnLinks;
    }

    public int getCriticalLinks() {
        return criticalLinks;
    }

    public void setCriticalLinks(int criticalLinks) {
        this.criticalLinks = criticalLinks;
    }

    public int getStaleLinks() {
        return staleLinks;
    }

    public void setStaleLinks(int staleLinks) {
        this.staleLinks = staleLinks;
    }

    public int getIdleLinks() {
        return idleLinks;
    }

    public void setIdleLinks(int idleLinks) {
        this.idleLinks = idleLinks;
    }

    public int getHealthScore() {
        return healthScore;
    }

    public void setHealthScore(int healthScore) {
        this.healthScore = healthScore;
    }

    public long getEventsTotal() {
        return eventsTotal;
    }

    public void setEventsTotal(long eventsTotal) {
        this.eventsTotal = eventsTotal;
    }

    public long getRetryRecoveredTotal() {
        return retryRecoveredTotal;
    }

    public void setRetryRecoveredTotal(long retryRecoveredTotal) {
        this.retryRecoveredTotal = retryRecoveredTotal;
    }

    public long getDeadLetterTotal() {
        return deadLetterTotal;
    }

    public void setDeadLetterTotal(long deadLetterTotal) {
        this.deadLetterTotal = deadLetterTotal;
    }

    public long getMaxStallSeconds() {
        return maxStallSeconds;
    }

    public void setMaxStallSeconds(long maxStallSeconds) {
        this.maxStallSeconds = maxStallSeconds;
    }

    public long getMaxLagSeconds() {
        return maxLagSeconds;
    }

    public void setMaxLagSeconds(long maxLagSeconds) {
        this.maxLagSeconds = maxLagSeconds;
    }

    public List<LinkHealthVO> getLinks() {
        return links;
    }

    public void setLinks(List<LinkHealthVO> links) {
        this.links = links;
    }
}
