/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.parser.model;

/**
 * 全量同步单表进度（写入 Meta.snapshot.tableProgress）。
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-08-11
 */
public class TableSyncProgress {

    /**
     * 页码，从 1 开始
     */
    private int pageIndex = 1;

    /**
     * 游标（主键逗号拼接）
     */
    private String cursor = "";

    /**
     * 是否已完成
     */
    private boolean done;

    /**
     * 分片边界（仅分片进度键使用，格式 "start~end"）。
     * <p>Wave31：分片并发读的断点按 {@code 组ID#s+下标} 分键保存；
     * 恢复时校验分片值域未漂移（源表 MIN/MAX 变化会改变切分），漂移则不复用旧断点。</p>
     */
    private String bounds = "";

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }

    public String getCursor() {
        return cursor;
    }

    public void setCursor(String cursor) {
        this.cursor = cursor;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public String getBounds() {
        return bounds;
    }

    public void setBounds(String bounds) {
        this.bounds = bounds == null ? "" : bounds;
    }
}
