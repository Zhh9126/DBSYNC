package org.dbdatasync.biz.sqlscan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wave24：PL/SQL 轻量 AST 结构化改写器。
 *
 * <p>此前 Wave17 的 SQL-P01~P06 只是正则「识别 + 提示」，Wave5 的
 * {@link SqlRewriteDraft} 更是裸字符串替换（会误伤字符串字面量与注释、无法区分
 * 函数/类型/标识符），从不敢对 PL/SQL 块输出改写稿。本类把 PL/SQL 对象解析为一棵
 * 轻量语法树（对象头 → 参数/返回类型 → DECLARE / BEGIN / EXCEPTION / END 区块 → 语句
 * → 词法 token），再在 token 层做方言映射：</p>
 *
 * <ul>
 *     <li>词法层保护：字符串、引号标识符、单行/块注释不参与替换；</li>
 *     <li>结构层识别：CREATE [OR REPLACE] PROCEDURE/FUNCTION/PACKAGE/TRIGGER/TYPE、
 *         DECLARE/BEGIN/EXCEPTION/END 区块与语句边界，输出结构摘要；</li>
 *     <li>规则层：NVL→COALESCE、SYSDATE→CURRENT_TIMESTAMP/NOW()、VARCHAR2→VARCHAR、
 *         NUMBER→NUMERIC、MINUS→EXCEPT、CLOB/BLOB 类型映射等机械等价项自动改写；</li>
 *     <li>语义层：DECODE、CONNECT BY、ROWNUM、%TYPE、BULK COLLECT、自治事务、
 *         包/触发器结构等无机械等价项列入 {@code manualItems}，不做伪改写。</li>
 * </ul>
 *
 * <p><strong>诚实边界</strong>：这是面向迁移评估的轻量结构化改写器，不是完整的
 * PL/SQL 编译器（不做语义分析、不保证改写后可直接执行）；输出仍是「改写草稿 +
 * 人工确认清单」，扫描结果保持 error 级，不冒充自动化闭环。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class PlSqlAstRewriter {

    /** 词法 token 类别 */
    private enum TokenType {
        WORD, NUMBER, STRING, QUOTED_IDENT, COMMENT, SYMBOL
    }

    private static final class Token {
        final TokenType type;
        final String text;
        final int start;
        final int end;

        Token(TokenType type, String text, int start, int end) {
            this.type = type;
            this.text = text;
            this.start = start;
            this.end = end;
        }

        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }
    }

    /** 解析出的 PL/SQL 结构树摘要 */
    public static final class AstSummary {
        private String objectType = "UNKNOWN";
        private String objectName = "";
        private boolean hasDeclare;
        private boolean hasBegin;
        private boolean hasException;
        private boolean hasEnd;
        private int statementCount;
        private int tokenCount;

        public String getObjectType() {
            return objectType;
        }

        public String getObjectName() {
            return objectName;
        }

        public boolean isHasDeclare() {
            return hasDeclare;
        }

        public boolean isHasBegin() {
            return hasBegin;
        }

        public boolean isHasException() {
            return hasException;
        }

        public boolean isHasEnd() {
            return hasEnd;
        }

        public int getStatementCount() {
            return statementCount;
        }

        public int getTokenCount() {
            return tokenCount;
        }

        /** 供报告展示的结构摘要 */
        public String describe() {
            return objectType + " " + objectName + " | declare=" + hasDeclare
                    + " begin=" + hasBegin + " exception=" + hasException
                    + " end=" + hasEnd + " | statements=" + statementCount
                    + " tokens=" + tokenCount;
        }
    }

    /** 改写结果 */
    public static final class RewriteResult {
        private final String rewrittenSql;
        private final List<String> appliedRules;
        private final List<String> manualItems;
        private final AstSummary ast;

        RewriteResult(String rewrittenSql, List<String> appliedRules,
                      List<String> manualItems, AstSummary ast) {
            this.rewrittenSql = rewrittenSql;
            this.appliedRules = Collections.unmodifiableList(appliedRules);
            this.manualItems = Collections.unmodifiableList(manualItems);
            this.ast = ast;
        }

        public String getRewrittenSql() {
            return rewrittenSql;
        }

        public List<String> getAppliedRules() {
            return appliedRules;
        }

        public List<String> getManualItems() {
            return manualItems;
        }

        public AstSummary getAst() {
            return ast;
        }

        /** 自动覆盖率：0~1，语义项越多越低 */
        public double getConfidence() {
            int total = appliedRules.size() + manualItems.size();
            return total == 0 ? 0d : (double) appliedRules.size() / total;
        }

        public boolean isChanged(String original) {
            return original != null && !original.equals(rewrittenSql);
        }
    }

    /** 查询子句关键字：NUMBER 出现在这些词前视为列名标识符 */
    private static final Set<String> QUERY_CLAUSE_KEYWORDS = new LinkedHashSet<>(
            java.util.Arrays.asList("FROM", "WHERE", "AND", "OR", "ON", "BY", "GROUP",
                    "ORDER", "HAVING", "JOIN", "SELECT", "INTO", "SET", "VALUES"));

    /** 机械等价映射（词法 token 大写 → 目标写法） */
    private static final Map<String, String> ORACLE_TO_PG = new LinkedHashMap<>();
    private static final Map<String, String> ORACLE_TO_MYSQL = new LinkedHashMap<>();
    /** 需要人工处理的语义结构关键字 */
    private static final Set<String> MANUAL_KEYWORDS = new LinkedHashSet<>();

    static {
        ORACLE_TO_PG.put("NVL", "COALESCE");
        ORACLE_TO_PG.put("SYSDATE", "CURRENT_TIMESTAMP");
        ORACLE_TO_PG.put("VARCHAR2", "VARCHAR");
        ORACLE_TO_PG.put("NVARCHAR2", "VARCHAR");
        ORACLE_TO_PG.put("NUMBER", "NUMERIC");
        ORACLE_TO_PG.put("CLOB", "TEXT");
        ORACLE_TO_PG.put("BLOB", "BYTEA");
        ORACLE_TO_PG.put("MINUS", "EXCEPT");
        ORACLE_TO_PG.put("TO_CHAR", "TO_CHAR");
        ORACLE_TO_MYSQL.put("NVL", "COALESCE");
        ORACLE_TO_MYSQL.put("IFNULL", "COALESCE");
        ORACLE_TO_MYSQL.put("SYSDATE", "NOW()");
        ORACLE_TO_MYSQL.put("VARCHAR2", "VARCHAR");
        ORACLE_TO_MYSQL.put("NVARCHAR2", "VARCHAR");
        ORACLE_TO_MYSQL.put("NUMBER", "DECIMAL");
        ORACLE_TO_MYSQL.put("CLOB", "LONGTEXT");
        ORACLE_TO_MYSQL.put("BLOB", "LONGBLOB");
        ORACLE_TO_MYSQL.put("MINUS", "EXCEPT");
        Collections.addAll(MANUAL_KEYWORDS,
                "NVL2", "DECODE", "ROWNUM", "CONNECT", "START", "PRIOR", "LEVEL",
                "BULK", "COLLECT", "EXECUTE", "IMMEDIATE", "PRAGMA", "AUTONOMOUS_TRANSACTION",
                "RETURNING", "ROWCOUNT", "SQLCODE", "SQLERRM", "RAISE_APPLICATION_ERROR",
                "PIPELINED", "REF", "CURSOR", "GOTO", "SAVEPOINT", "DBMS_");
    }

    private PlSqlAstRewriter() {
    }

    /**
     * 结构化改写 PL/SQL 对象。
     *
     * @param sql            原始 SQL（可为整段脚本）
     * @param sourceDialect  源方言名（用于判断是否走 Oracle 系映射）
     * @param targetDialect  目标方言名（pg / mysql 家族）
     * @return 改写稿、已应用规则、人工项清单与 AST 摘要
     */
    public static RewriteResult rewrite(String sql, String sourceDialect, String targetDialect) {
        if (sql == null || sql.isEmpty()) {
            return new RewriteResult(sql, Collections.<String>emptyList(),
                    Collections.<String>emptyList(), new AstSummary());
        }
        List<Token> tokens = tokenize(sql);
        AstSummary ast = parseAst(tokens);
        Map<String, String> rules = rulesFor(sourceDialect, targetDialect);

        List<int[]> edits = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        Set<String> manual = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type != TokenType.WORD) {
                continue;
            }
            String upper = token.upper();
            if (MANUAL_KEYWORDS.contains(upper)) {
                manual.add(upper);
                continue;
            }
            String replacement = rules.get(upper);
            if (replacement == null || replacement.equals(upper)) {
                continue;
            }
            // NUMBER 是类型声明还是列名标识符难以静态判定：
            // 只有出现在查询子句关键字前（select number from ... 等）才视为标识符跳过，
            // 类型声明位置（RETURN NUMBER AS / v NUMBER := / p NUMBER DEFAULT）一律改写
            if ("NUMBER".equals(upper) && nextIsQueryClauseKeyword(tokens, i)) {
                continue;
            }
            // edits 与 replacements 一一对应，按倒序应用避免偏移漂移
            edits.add(new int[]{token.start, token.end});
            replacements.add(replacement);
            applied.add(upper + "→" + replacement);
        }
        String rewritten = applyEdits(sql, edits, replacements);
        return new RewriteResult(rewritten, applied, new ArrayList<>(manual), ast);
    }

    private static String applyEdits(String sql, List<int[]> edits, List<String> replacements) {
        if (edits.isEmpty()) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql);
        for (int i = edits.size() - 1; i >= 0; i--) {
            int[] edit = edits.get(i);
            out.replace(edit[0], edit[1], replacements.get(i));
        }
        return out.toString();
    }

    /** NUMBER 后跟查询子句关键字时视为列名/标识符，不参与类型改写。 */
    private static boolean nextIsQueryClauseKeyword(List<Token> tokens, int index) {
        for (int i = index + 1; i < tokens.size(); i++) {
            Token next = tokens.get(i);
            if (next.type == TokenType.COMMENT) {
                continue;
            }
            if (next.type != TokenType.WORD) {
                return false;
            }
            String upper = next.upper();
            return QUERY_CLAUSE_KEYWORDS.contains(upper);
        }
        return false;
    }

    private static Map<String, String> rulesFor(String sourceDialect, String targetDialect) {
        String src = sourceDialect == null ? "" : sourceDialect.toLowerCase(Locale.ROOT);
        String tgt = targetDialect == null ? "" : targetDialect.toLowerCase(Locale.ROOT);
        // 目标为 Oracle 家族（含达梦）时不改写，避免反向污染
        if (tgt.contains("oracle") || tgt.contains("dm")) {
            return Collections.emptyMap();
        }
        boolean pgFamily = tgt.contains("pg") || tgt.contains("kingbase")
                || tgt.contains("opengauss") || tgt.contains("postgre");
        if (pgFamily) {
            return ORACLE_TO_PG;
        }
        if (tgt.contains("mysql") || tgt.contains("maria") || tgt.contains("tidb")) {
            return ORACLE_TO_MYSQL;
        }
        // 未指定目标方言时按源方言推断：Oracle 系默认给 PG 映射
        return src.contains("oracle") || src.isEmpty() ? ORACLE_TO_PG : Collections.emptyMap();
    }

    /** 词法分析：保护字符串、引号标识符与注释，其余拆成 WORD / NUMBER / SYMBOL。 */
    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2);
                end = end < 0 ? n : end;
                tokens.add(new Token(TokenType.COMMENT, sql.substring(i, end), i, end));
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                tokens.add(new Token(TokenType.COMMENT, sql.substring(i, end), i, end));
                i = end;
                continue;
            }
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (sql.charAt(j) == '\'') {
                        if (j + 1 < n && sql.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        j++;
                        break;
                    }
                    j++;
                }
                tokens.add(new Token(TokenType.STRING, sql.substring(i, j), i, j));
                i = j;
                continue;
            }
            if (c == '"') {
                int j = sql.indexOf('"', i + 1);
                j = j < 0 ? n : j + 1;
                tokens.add(new Token(TokenType.QUOTED_IDENT, sql.substring(i, j), i, j));
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$' || c == '#') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j))
                        || sql.charAt(j) == '_' || sql.charAt(j) == '$'
                        || sql.charAt(j) == '#' || sql.charAt(j) == '%')) {
                    j++;
                }
                tokens.add(new Token(TokenType.WORD, sql.substring(i, j), i, j));
                i = j;
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && (Character.isDigit(sql.charAt(j)) || sql.charAt(j) == '.')) {
                    j++;
                }
                tokens.add(new Token(TokenType.NUMBER, sql.substring(i, j), i, j));
                i = j;
                continue;
            }
            // 双字符操作符（||、:=、<=、>=、<>、!=）
            if (i + 1 < n) {
                String pair = sql.substring(i, i + 2);
                if ("||".equals(pair) || ":=".equals(pair) || "<=".equals(pair)
                        || ">=".equals(pair) || "<>".equals(pair) || "!=".equals(pair)) {
                    tokens.add(new Token(TokenType.SYMBOL, pair, i, i + 2));
                    i += 2;
                    continue;
                }
            }
            tokens.add(new Token(TokenType.SYMBOL, String.valueOf(c), i, i + 1));
            i++;
        }
        return tokens;
    }

    /** 结构解析：对象头 + 区块 + 语句边界，形成轻量语法树摘要。 */
    private static AstSummary parseAst(List<Token> tokens) {
        AstSummary ast = new AstSummary();
        ast.tokenCount = tokens.size();
        int statements = 0;
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type == TokenType.SYMBOL) {
                if ("(".equals(token.text)) {
                    depth++;
                } else if (")".equals(token.text)) {
                    depth = Math.max(0, depth - 1);
                } else if (";".equals(token.text) && depth == 0) {
                    statements++;
                }
                continue;
            }
            if (token.type != TokenType.WORD) {
                continue;
            }
            String upper = token.upper();
            if (depth == 0) {
                switch (upper) {
                    case "DECLARE":
                        ast.hasDeclare = true;
                        break;
                    case "BEGIN":
                        ast.hasBegin = true;
                        break;
                    case "EXCEPTION":
                        ast.hasException = true;
                        break;
                    case "END":
                        ast.hasEnd = true;
                        break;
                    default:
                        break;
                }
            }
            if ("CREATE".equals(upper) && "UNKNOWN".equals(ast.objectType)) {
                // CREATE [OR REPLACE] [EDITIONABLE] <object kind> <name>
                int j = i + 1;
                while (j < tokens.size() && tokens.get(j).type != TokenType.WORD) {
                    j++;
                }
                String first = j < tokens.size() ? tokens.get(j).upper() : "";
                if ("OR".equals(first)) {
                    j += 2;
                } else if ("EDITIONABLE".equals(first) || "NONEDITIONABLE".equals(first)) {
                    j++;
                }
                if (j < tokens.size()) {
                    String kind = tokens.get(j).upper();
                    if ("PROCEDURE".equals(kind) || "FUNCTION".equals(kind)
                            || "PACKAGE".equals(kind) || "TRIGGER".equals(kind)
                            || "TYPE".equals(kind)) {
                        ast.objectType = kind;
                        int nameIndex = j + 1;
                        if (nameIndex < tokens.size() && "BODY".equals(tokens.get(nameIndex).upper())) {
                            ast.objectType = kind + "_BODY";
                            nameIndex++;
                        }
                        if (nameIndex < tokens.size()) {
                            ast.objectName = tokens.get(nameIndex).text;
                        }
                    }
                }
            }
        }
        ast.statementCount = statements;
        return ast;
    }
}
