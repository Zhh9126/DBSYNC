package org.dbdatasync.biz.sqlscan;

import java.util.ArrayList;
import java.util.List;

/**
 * DDL 建表语句转换草稿 VO（Wave10 P1-1）。
 *
 * <p>仅为字符串级类型映射的<strong>草稿 / 建议</strong>，不自动执行、不可直接当作可执行产物；
 * error 级类型不生成草稿（{@code draftDdl} 为空、{@code level=error}）；
 * 无映射命中时 {@code changed=false} 且返回原 SQL、不加注释头。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class DdlDraft {

    /** 原始 DDL */
    private String originalDdl;

    /** 转换草稿 DDL（无改动时为原 SQL；error 级为空） */
    private String draftDdl;

    /** 是否产生实际改动 */
    private boolean changed;

    /** 级别：warn=有 warn 级映射改动 / error=error 级需人工重写 / info=无改动 */
    private String level;

    /** 说明文案（草稿标注 / 需人工重写 / 无映射命中） */
    private String note;

    /** 命中的类型名列表 */
    private List<String> mappedTypes = new ArrayList<>();

    public String getOriginalDdl() {
        return originalDdl;
    }

    public void setOriginalDdl(String originalDdl) {
        this.originalDdl = originalDdl;
    }

    public String getDraftDdl() {
        return draftDdl;
    }

    public void setDraftDdl(String draftDdl) {
        this.draftDdl = draftDdl;
    }

    public boolean isChanged() {
        return changed;
    }

    public void setChanged(boolean changed) {
        this.changed = changed;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public List<String> getMappedTypes() {
        return mappedTypes;
    }

    public void setMappedTypes(List<String> mappedTypes) {
        this.mappedTypes = mappedTypes == null ? new ArrayList<>() : mappedTypes;
    }
}
