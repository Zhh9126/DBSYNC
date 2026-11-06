package org.dbdatasync.biz.sqlscan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描对象扩展（Wave10 P1-2，保守深度）。
 *
 * <p>仅对 {@code CREATE (OR REPLACE)? (PROCEDURE|FUNCTION|TRIGGER|VIEW)} 整段定义文本
 * 做规则族命中 + {@code unparseable} 降级粗粒度提示：把每段对象定义作为单条 statement
 * 交给既有 {@link SqlCompatibilityScanner#scan(String, String, String)}，走 Wave5 已有
 * {@code fallbackTextScan} 降级路径（jsqlparser 4.9 无法解析 → {@code sql-unparseable} warn
 * + 结构/函数族命中），并在降级 issue 的 message/suggestion 追加
 * 「{@value #FALLBACK_NOTE}」。</p>
 *
 * <p><strong>不识别 PL/SQL 块内语句、不承诺完整 PL/SQL 语义解析或 AST 级重写</strong>；
 * 不改变 Wave5 既有规则命中结果与 error/warn/info 分级语义。
 * {@link SqlCompatibilityScanner} / {@link SqlScanResult} / {@link SqlCompatibilityIssue}
 * 三者零改动（仅通过 setter 补充降级 issue 的说明文本）。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class SqlObjectDefinitionExpander {

    /** 降级提示（追加到 sql-unparseable / 方言不支持类 issue 的说明与建议） */
    public static final String FALLBACK_NOTE = "仅文本降级扫描 / 需人工确认";

    /** CREATE 对象定义起始（多行、大小写不敏感） */
    private static final Pattern OBJECT_START = Pattern.compile(
            "(?im)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:PROCEDURE|FUNCTION|TRIGGER|VIEW)\\b");

    private final SqlCompatibilityScanner scanner;

    public SqlObjectDefinitionExpander() {
        this(new SqlCompatibilityScanner());
    }

    public SqlObjectDefinitionExpander(SqlCompatibilityScanner scanner) {
        this.scanner = scanner == null ? new SqlCompatibilityScanner() : scanner;
    }

    /**
     * 把整段 CREATE 对象定义交给既有扫描器并合并结果。
     *
     * @param objectDefinitions 对象定义文本（可含多段 CREATE / 普通 SQL / PL/SQL 块）
     * @param src               源方言
     * @param tgt               目标方言
     * @return 合并后的扫描结果（口径对齐 {@link SqlScanResult}）
     */
    public SqlScanResult expandAndScan(String objectDefinitions, String src, String tgt) {
        SqlScanResult merged = new SqlScanResult();
        merged.setSourceDialect(src);
        merged.setTargetDialect(tgt);
        if (objectDefinitions == null || objectDefinitions.trim().isEmpty()) {
            return merged;
        }
        List<String> segments = splitObjectDefinitions(objectDefinitions);
        boolean anyScanned = false;
        for (String segment : segments) {
            if (segment == null || segment.trim().isEmpty()) {
                continue;
            }
            SqlScanResult partial;
            try {
                partial = scanner.scan(segment, src, tgt);
            } catch (RuntimeException e) {
                // 单段解析异常不中断整体（延续 Wave5 降级语义）
                continue;
            }
            if (partial == null) {
                continue;
            }
            anyScanned = anyScanned || partial.isScanned();
            merged.setStatementCount(merged.getStatementCount() + partial.getStatementCount());
            merged.setSqlLength(merged.getSqlLength() + partial.getSqlLength());
            boolean objectDefinition = isObjectDefinition(segment);
            for (SqlCompatibilityIssue issue : partial.getIssues()) {
                if (issue == null) {
                    continue;
                }
                if (objectDefinition && isFallbackIssue(issue)) {
                    appendFallbackNote(issue);
                }
                merged.addIssue(issue);
            }
        }
        merged.setScanned(anyScanned);
        return merged;
    }

    /**
     * 是否为 CREATE 对象定义（PROCEDURE / FUNCTION / TRIGGER / VIEW）。
     */
    private boolean isObjectDefinition(String statement) {
        return statement != null && OBJECT_START.matcher(statement).find();
    }

    /**
     * 按 CREATE 对象定义边界切段；首段之前的普通 SQL 文本单独成段。
     */
    private List<String> splitObjectDefinitions(String text) {
        List<String> segments = new ArrayList<>();
        Matcher matcher = OBJECT_START.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        if (starts.isEmpty()) {
            segments.add(text);
            return segments;
        }
        String head = text.substring(0, starts.get(0)).trim();
        if (!head.isEmpty()) {
            segments.add(head);
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String segment = text.substring(from, to).trim();
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    /** 降级类 issue：解析失败或方言不受支持 */
    private boolean isFallbackIssue(SqlCompatibilityIssue issue) {
        String code = issue.getCode();
        return "sql-unparseable".equals(code) || "sql-unsupported-dialect".equals(code);
    }

    private void appendFallbackNote(SqlCompatibilityIssue issue) {
        String message = issue.getMessage() == null ? "" : issue.getMessage();
        if (!message.contains(FALLBACK_NOTE)) {
            issue.setMessage(message + "（" + FALLBACK_NOTE + "）");
        }
        String suggestion = issue.getSuggestion() == null ? "" : issue.getSuggestion();
        if (!suggestion.contains(FALLBACK_NOTE)) {
            issue.setSuggestion(suggestion + "（" + FALLBACK_NOTE + "）");
        }
    }
}
