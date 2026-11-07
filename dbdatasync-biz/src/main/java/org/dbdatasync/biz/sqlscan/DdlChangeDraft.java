package org.dbdatasync.biz.sqlscan;

import java.util.ArrayList;
import java.util.List;

/**
 * DDL 变更辅助结果（ALTER TABLE 拆解 → 类型映射 → 目标端草稿 + 风险标注）。
 *
 * <p>纯字符串级产物，不连库、不执行 DDL；草稿仅供人工确认。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class DdlChangeDraft {

    /** 原始 ALTER SQL */
    private String originalSql;

    /** 拆解出的变更单元列表 */
    private List<DdlChangeUnit> units = new ArrayList<>();

    /** 重构后的目标端 ALTER（error 级为空）；带 DRAFT_HEADER */
    private String targetDdlDraft;

    /** 是否发生类型映射改动 */
    private boolean changed;

    /** 整体级别（取最严重） */
    private String level;

    /** 命中有改动的源类型片段 */
    private List<String> mappedTypes = new ArrayList<>();

    /** 各单元风险提示 */
    private List<String> risks = new ArrayList<>();

    /** 备注说明 */
    private String note;

    /** 提示语 */
    private String hint = "草稿/需人工确认/不自动执行";

    /** 是否阻断 */
    private boolean blocking = false;

    public DdlChangeDraft() {
    }

    public String getOriginalSql() {
        return originalSql;
    }

    public void setOriginalSql(String originalSql) {
        this.originalSql = originalSql;
    }

    public List<DdlChangeUnit> getUnits() {
        return units;
    }

    public void setUnits(List<DdlChangeUnit> units) {
        this.units = units;
    }

    public String getTargetDdlDraft() {
        return targetDdlDraft;
    }

    public void setTargetDdlDraft(String targetDdlDraft) {
        this.targetDdlDraft = targetDdlDraft;
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

    public List<String> getMappedTypes() {
        return mappedTypes;
    }

    public void setMappedTypes(List<String> mappedTypes) {
        this.mappedTypes = mappedTypes;
    }

    public List<String> getRisks() {
        return risks;
    }

    public void setRisks(List<String> risks) {
        this.risks = risks;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
    }
}
