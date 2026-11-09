/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.vo;

/**
 * 单条实时同步链路的健康视图。
 *
 * @author wuji
 * @version 1.0.0
 */
public class LinkHealthVO {

    private String metaId;
    private String mappingId;
    private String mappingName;
    private String model;
    private int state;
    private String stateName;
    private boolean running;

    private String phase;
    private String position;
    private long lastEventTime;
    private long stallSeconds;
    /** Wave23：端到端复制延迟（秒），仅 lagSource=source 时为真实值 */
    private long lagSeconds;
    /** source / estimated / unknown */
    private String lagSource;
    private long lastSourceEventTime;
    private boolean hasEvent;

    private long eventsTotal;
    private long retryRecovered;
    private long deadLetters;
    private long total;
    private long success;
    private long fail;
    private double failRate;

    private String level;
    private String levelLabel;
    private int score;
    private String reason;

    public String getMetaId() {
        return metaId;
    }

    public void setMetaId(String metaId) {
        this.metaId = metaId;
    }

    public String getMappingId() {
        return mappingId;
    }

    public void setMappingId(String mappingId) {
        this.mappingId = mappingId;
    }

    public String getMappingName() {
        return mappingName;
    }

    public void setMappingName(String mappingName) {
        this.mappingName = mappingName;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public String getStateName() {
        return stateName;
    }

    public void setStateName(String stateName) {
        this.stateName = stateName;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public long getLastEventTime() {
        return lastEventTime;
    }

    public void setLastEventTime(long lastEventTime) {
        this.lastEventTime = lastEventTime;
    }

    public long getStallSeconds() {
        return stallSeconds;
    }

    public void setStallSeconds(long stallSeconds) {
        this.stallSeconds = stallSeconds;
    }

    public long getLagSeconds() {
        return lagSeconds;
    }

    public void setLagSeconds(long lagSeconds) {
        this.lagSeconds = lagSeconds;
    }

    public String getLagSource() {
        return lagSource;
    }

    public void setLagSource(String lagSource) {
        this.lagSource = lagSource;
    }

    public long getLastSourceEventTime() {
        return lastSourceEventTime;
    }

    public void setLastSourceEventTime(long lastSourceEventTime) {
        this.lastSourceEventTime = lastSourceEventTime;
    }

    public boolean isHasEvent() {
        return hasEvent;
    }

    public void setHasEvent(boolean hasEvent) {
        this.hasEvent = hasEvent;
    }

    public long getEventsTotal() {
        return eventsTotal;
    }

    public void setEventsTotal(long eventsTotal) {
        this.eventsTotal = eventsTotal;
    }

    public long getRetryRecovered() {
        return retryRecovered;
    }

    public void setRetryRecovered(long retryRecovered) {
        this.retryRecovered = retryRecovered;
    }

    public long getDeadLetters() {
        return deadLetters;
    }

    public void setDeadLetters(long deadLetters) {
        this.deadLetters = deadLetters;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getSuccess() {
        return success;
    }

    public void setSuccess(long success) {
        this.success = success;
    }

    public long getFail() {
        return fail;
    }

    public void setFail(long fail) {
        this.fail = fail;
    }

    public double getFailRate() {
        return failRate;
    }

    public void setFailRate(double failRate) {
        this.failRate = failRate;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getLevelLabel() {
        return levelLabel;
    }

    public void setLevelLabel(String levelLabel) {
        this.levelLabel = levelLabel;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
