package org.dbdatasync.biz.sqlscan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDL 变更辅助编排服务（Wave13 B 线）。
 *
 * <p>流程：拆解 → 类型映射 → 重建目标端 ALTER 草稿 → 风险标注。
 * 纯字符串处理，<strong>无 JDBC / 无 import 任何运行时 DDL 执行类</strong>，
 * 不连库、不执行 DDL；error 级类型不生成草稿。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class DdlChangeAssistService {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private static final Pattern P_ALTER =
            Pattern.compile("^\\s*ALTER\\s+TABLE\\s+[\\w$.]+\\b\\s*(.*)$", FLAGS);

    private static final String DROP_MARK = "(drop)";

    /**
     * 生成 DDL 变更辅助草稿。
     *
     * @param sql           原始 ALTER SQL
     * @param sourceDialect 源方言（保留契约参数，本轮映射与方言无关）
     * @param targetDialect 目标方言（保留契约参数，本轮映射与方言无关）
     * @return 变更辅助草稿 VO
     */
    public DdlChangeDraft assist(String sql, String sourceDialect, String targetDialect) {
        DdlChangeDraft draft = new DdlChangeDraft();
        String original = sql == null ? "" : sql;
        draft.setOriginalSql(original);

        List<DdlChangeUnit> units = DdlChangeSplitter.split(original, sourceDialect, targetDialect);
        draft.setUnits(units);

        if (units.isEmpty()) {
            draft.setChanged(false);
            draft.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
            draft.setNote("无 DDL 变更识别，请检查输入");
            return draft;
        }

        // 收集风险与映射类型，并判定是否有 error 级
        boolean hasError = false;
        for (DdlChangeUnit u : units) {
            if (SqlCompatibilityIssue.LEVEL_ERROR.equals(u.getLevel())) {
                hasError = true;
            }
            draft.getRisks().add(u.getRiskNote());
            if (u.getSourceType() != null && u.getTargetType() != null
                    && !u.getSourceType().equals(u.getTargetType())
                    && !DROP_MARK.equals(u.getSourceType())) {
                draft.getMappedTypes().add(u.getSourceType());
            }
        }

        if (hasError) {
            draft.setTargetDdlDraft("");
            draft.setChanged(false);
            draft.setLevel(SqlCompatibilityIssue.LEVEL_ERROR);
            draft.setNote("error 级类型需人工重写，不生成草稿");
            return draft;
        }

        // 正常路径：按原语句顺序重建目标端 ALTER（仅替换有改动的源类型片段）
        String rebuilt = rebuild(original, units);
        boolean changed = false;
        for (DdlChangeUnit u : units) {
            if (u.getTargetType() != null && u.getSourceType() != null
                    && !u.getTargetType().equals(u.getSourceType())) {
                changed = true;
                break;
            }
        }
        draft.setChanged(changed);
        draft.setHint("草稿/需人工确认/不自动执行");
        draft.setBlocking(false);
        if (changed) {
            draft.setTargetDdlDraft(DdlDraftGenerator.DRAFT_HEADER + "\n" + rebuilt);
            draft.setLevel(SqlCompatibilityIssue.LEVEL_WARN);
            draft.setNote("已生成目标端类型映射草稿，需人工确认后使用");
        } else {
            draft.setTargetDdlDraft(original);
            draft.setLevel(SqlCompatibilityIssue.LEVEL_INFO);
            draft.setNote("无映射命中，返回原 SQL。");
        }
        return draft;
    }

    private String rebuild(String original, List<DdlChangeUnit> units) {
        String cleaned = stripComments(original);
        String[] stmts = cleaned.split(";");
        List<String> out = new ArrayList<>();
        int idx = 0;
        for (String raw : stmts) {
            String stmt = raw == null ? "" : raw.trim();
            if (stmt.isEmpty()) {
                continue;
            }
            DdlChangeUnit unit = idx < units.size() ? units.get(idx) : null;
            idx++;
            if (unit != null && isTypeOp(unit.getOperation())
                    && unit.getSourceType() != null && !unit.getSourceType().isEmpty()
                    && unit.getTargetType() != null && !unit.getTargetType().isEmpty()
                    && !unit.getTargetType().equals(unit.getSourceType())) {
                String replaced = stmt.replaceFirst(
                        Pattern.quote(unit.getSourceType()),
                        Matcher.quoteReplacement(unit.getTargetType()));
                out.add(replaced);
            } else {
                out.add(stmt);
            }
        }
        return String.join(";\n", out);
    }

    private static boolean isTypeOp(String op) {
        return "ADD".equals(op) || "MODIFY".equals(op) || "CHANGE".equals(op);
    }

    private static String stripComments(String sql) {
        String s = sql;
        s = s.replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        s = s.replaceAll("--[^\\n]*", " ");
        return s;
    }
}
