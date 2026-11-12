package org.dbdatasync.biz.sqlscan;

import java.util.ArrayList;

/**
 * DDL 建表语句转换草稿生成器（Wave10 P1-1）。
 *
 * <p>纯字符串处理：类型映射走 {@link DdlTypeMapping}（只读复用 Wave8
 * {@link SqlRewriteDraft#baseTypeRewriteMap()} 与 Wave5
 * {@link SqlCompatibilityRules#allTypeRules()} 级别）。</p>
 *
 * <p>分级策略：
 * <ul>
 *   <li>命中 error 级类型（ROWID/UROWID）→ {@code changed=false, level=error}，不生成草稿，需人工重写</li>
 *   <li>有 warn 级映射改动 → 首行加 {@value #DRAFT_HEADER} 注释，{@code changed=true, level=warn}</li>
 *   <li>无映射命中 → 返回原 SQL，{@code changed=false}，不加注释</li>
 * </ul>
 *
 * <p><strong>无任何 JDBC / Connection / DriverManager / 执行调用</strong>；不连库、不启服务、
 * 不改变 Wave5「评估不误导」与 Wave8 既有改写草稿语义。sourceDialect/targetDialect 仅保留
 * 契约参数，本轮类型映射与方言无关（Q3 唯一映射表）。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public final class DdlDraftGenerator {

    /** 草稿首行注释头 */
    public static final String DRAFT_HEADER = "-- [DDL 转换草稿 / 需人工确认 / 不自动执行]";

    private static final String NOTE_DRAFT =
            "草稿 / 需人工确认 / 不自动执行；仅为字符串级类型映射，请人工复核后使用。";
    private static final String NOTE_ERROR =
            "error 级类型（ROWID/UROWID 等目标无等价语义）需人工重写，不生成草稿。";
    private static final String NOTE_NONE = "无映射命中，返回原 SQL。";

    private final DdlTypeMapping typeMapping = new DdlTypeMapping();

    /**
     * 生成 DDL 转换草稿。
     *
     * @param ddl           原始建表 DDL 文本
     * @param sourceDialect 源方言（保留契约参数，本轮不参与匹配差异）
     * @param targetDialect 目标方言（保留契约参数，本轮不参与匹配差异）
     * @return 草稿 VO
     */
    public DdlDraft generate(String ddl, String sourceDialect, String targetDialect) {
        DdlDraft draft = new DdlDraft();
        String original = ddl == null ? "" : ddl;
        draft.setOriginalDdl(original);
        draft.setMappedTypes(new ArrayList<String>());

        if (original.trim().isEmpty()) {
            draft.setDraftDdl(original);
            draft.setChanged(false);
            draft.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
            draft.setNote(NOTE_NONE);
            return draft;
        }

        draft.setMappedTypes(typeMapping.matchedTypes(original));
        if (typeMapping.hasErrorLevel(original)) {
            draft.setDraftDdl("");
            draft.setChanged(false);
            draft.setLevel(SqlCompatibilityIssue.LEVEL_ERROR);
            draft.setNote(NOTE_ERROR);
            return draft;
        }

        String mapped = typeMapping.map(original);
        boolean changed = mapped != null && !original.equals(mapped);
        if (!changed) {
            draft.setDraftDdl(original);
            draft.setChanged(false);
            draft.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
            draft.setNote(NOTE_NONE);
            return draft;
        }

        draft.setDraftDdl(DRAFT_HEADER + "\n" + mapped);
        draft.setChanged(true);
        draft.setLevel(SqlCompatibilityIssue.LEVEL_WARN);
        draft.setNote(NOTE_DRAFT);
        return draft;
    }
}
