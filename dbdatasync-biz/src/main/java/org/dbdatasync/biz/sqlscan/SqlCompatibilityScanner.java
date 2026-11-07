package org.dbdatasync.biz.sqlscan;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.cnfexpression.MultiAndExpression;
import org.dbdatasync.sdk.util.SqlParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 兼容度扫描引擎（纯离线静态扫描，不做语法转换）。
 *
 * <p>流程：入参（SQL 文本 + 源方言 + 目标方言）→ 逐条解析 Statement →
 * 规则命中（函数/操作符/结构/类型）→ 收集为问题项 → 分级统计输出。
 *
 * <p>实现要点：
 * <ul>
 *   <li>用 {@code SqlParserUtil.parseStatements} 按 ; 拆多语句并逐条解析；段落不完整解析失败则降级为单条正则扫描</li>
 *   <li>AST 遍历：对每条语句 toString 后做精确/包含性关键字命中，避免逐节点深遍历（jsqlparser 4.9 结构与方言覆盖有限）</li>
 *   <li>函数族命中：Oracle 系→MySQL 系等 pair 规则表；结构族（CONNECT BY/MINUS/PIVOT…）与类型族按 SQL 文本特征命中</li>
 *   <li>结果级别：error（目标无等价，必须改造）/ warn（可改写，给示意）/ info（仅提示）</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
@Component
public class SqlCompatibilityScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlCompatibilityScanner.class);

    /** Wave17：PL/SQL / 存储过程对象识别（CREATE [OR REPLACE] PROCEDURE|FUNACTION|TRIGGER|PACKAGE|PACKAGE BODY|TYPE [BODY] <name>） */
    private static final java.util.regex.Pattern P_PLSQL_OBJECT = java.util.regex.Pattern.compile(
            "CREATE\\s+(?:OR\\s+REPLACE\\s+)?(PROCEDURE|FUNCTION|TRIGGER|PACKAGE\\s+BODY|PACKAGE|TYPE\\s+BODY|TYPE)\\s+([\\w$.]+)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 扫描入口。
     *
     * @param sql            SQL 文本（可含多语句）
     * @param sourceDialect  源方言（连接器类型文本，如 "Oracle"）
     * @param targetDialect  目标方言
     */
    public SqlScanResult scan(String sql, String sourceDialect, String targetDialect) {
        SqlScanResult result = new SqlScanResult();
        result.setSourceDialect(sourceDialect);
        result.setTargetDialect(targetDialect);
        if (sql == null) {
            sql = "";
        }
        result.setSqlLength(sql.length());
        SqlDialect src = SqlDialect.parse(sourceDialect);
        SqlDialect tgt = SqlDialect.parse(targetDialect);

        // 方言解析失败：降级为仅提示
        if (src == null || tgt == null) {
            result.setScanned(false);
            result.addIssue(new SqlCompatibilityIssue(
                    SqlCompatibilityIssue.LEVEL_INFO, "other", "sql-unsupported-dialect", "",
                    sql, "无法识别的源/目标方言，未执行语法扫描", "请选择支持方言（MySQL/Oracle/PostgreSQL/Kingbase/DM/OceanBase）"));
            return result;
        }

        result.setScanned(true);
        List<String> statements = splitStatements(sql);
        result.setStatementCount(statements.size());

        for (String statementSql : statements) {
            if (statementSql == null || statementSql.trim().isEmpty()) {
                continue;
            }
            try {
                scanStatement(statementSql, src, tgt, result);
            } catch (Exception e) {
                // 单条解析失败：降级为文本特征扫描（保证不整体中断）
                LOGGER.debug("statement parse failed, fallback to text scan: {}", e.getMessage());
                fallbackTextScan(statementSql, src, tgt, result);
            }
        }
        return result;
    }

    /** 按分号拆语句（跳过字符串/注释内的分号），并做简单 trim */
    List<String> splitStatements(String sql) {
        List<String> list = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                buf.append(c);
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                    buf.append("*/");
                    i++;
                    inBlockComment = false;
                } else {
                    buf.append(c);
                }
                continue;
            }
            if (inSingle) {
                buf.append(c);
                if (c == '\'') {
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                        buf.append('\'');
                        i++;
                    } else {
                        inSingle = false;
                    }
                }
                continue;
            }
            if (inDouble) {
                buf.append(c);
                if (c == '"') {
                    if (i + 1 < n && sql.charAt(i + 1) == '"') {
                        buf.append('"');
                        i++;
                    } else {
                        inDouble = false;
                    }
                }
                continue;
            }
            switch (c) {
                case '\'':
                    inSingle = true;
                    buf.append(c);
                    break;
                case '"':
                    inDouble = true;
                    buf.append(c);
                    break;
                case '-':
                    if (i + 1 < n && sql.charAt(i + 1) == '-') {
                        inLineComment = true;
                        buf.append("--");
                        i++;
                    } else {
                        buf.append(c);
                    }
                    break;
                case '/':
                    if (i + 1 < n && sql.charAt(i + 1) == '*') {
                        inBlockComment = true;
                        buf.append("/*");
                        i++;
                    } else {
                        buf.append(c);
                    }
                    break;
                case ';':
                    String stmt = buf.toString().trim();
                    if (!stmt.isEmpty()) {
                        list.add(stmt);
                    }
                    buf.setLength(0);
                    break;
                default:
                    buf.append(c);
            }
        }
        String tail = buf.toString().trim();
        if (!tail.isEmpty()) {
            list.add(tail);
        }
        return list;
    }

    /** 单条语句扫描：尝试 AST 解析 → 命中；解析失败走文本特征 */
    private void scanStatement(String sql, SqlDialect src, SqlDialect tgt, SqlScanResult result) {
        Statement statement = null;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            fallbackTextScan(sql, src, tgt, result);
            return;
        }
        if (statement == null) {
            fallbackTextScan(sql, src, tgt, result);
            return;
        }

        List<String> tokens = normalizeTokens(sql);

        // 1) 函数族：命中表按 pair 查
        applyFunctionRules(tokens, sql, src, tgt, result);
        // 2) 结构族（关键字级）
        applyClauseRules(tokens, sql, src, tgt, result);
        // 3) 操作符族
        applyOperatorRules(tokens, sql, src, tgt, result);
        // 4) 类型族
        applyTypeRules(tokens, sql, src, tgt, result);

        // 5) Wave17：PL/SQL / 存储过程对象识别
        applyPlsqlObjectRules(sql, src, tgt, result);

        // 6) 附加：未解析成功但文本含专有特征的（已在 fallback 处理）
    }

    /** 把 SQL 文本转成小写 token 列表（去引号、统一空白），供规则命中 */
    private List<String> normalizeTokens(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        // 去掉字符串字面量，避免误命中（简单处理：保留，规则命中要求词边界）
        // 注意：拆分符集合只保留字母数字下划线#，括号/点/逗号等均作为分隔，
        // 否则 "NVL(a.amount" 会粘连成单个 token 导致函数名规则失配。
        List<String> tokens = new ArrayList<>();
        String[] parts = upper.split("[^a-zA-Z0-9_#]");
        for (String p : parts) {
            if (!p.isEmpty()) {
                tokens.add(p.trim());
            }
        }
        return tokens;
    }

    /** token 是否包含指定关键字（词边界，忽略大小写） */
    private boolean hasToken(List<String> tokens, String keyword) {
        String k = keyword.toUpperCase(Locale.ROOT);
        for (String t : tokens) {
            if (t.equals(k)) {
                return true;
            }
        }
        return false;
    }

    // ============ 函数族 ============

    private void applyFunctionRules(List<String> tokens, String sql, SqlDialect src, SqlDialect tgt,
                                    SqlScanResult result) {
        // 函数族按家族 pair 键（oracle→pg 覆盖 oracle→kingbase 等）
        String pair = familyPairKey(src, tgt);
        SqlCompatibilityRules.Rule[] rules = SqlCompatibilityRules.mergedFunctions(pair);
        if (rules == null) {
            return;
        }
        for (SqlCompatibilityRules.Rule rule : rules) {
            String elem = rule.element;
            // 多元素（如 "trunc(日期)"、"empty_clob/empty_blob"）取首 token
            String head = elem.split("[//(]")[0].trim();
            boolean hit;
            if (elem.contains(" ")) {
                // 复合项（distinct on / limit offset / on conflict…）：全文包含即可
                hit = sql.toLowerCase(Locale.ROOT).contains(elem.toLowerCase(Locale.ROOT));
            } else if (containsFunctionCall(sql, head)) {
                // 常规函数：要求「函数名(」调用形态 + token 词边界
                hit = hasToken(tokens, head);
            } else {
                // 无括号函数名（SYSDATE/ILIKE/generate_series…）：全文包含 + 词边界
                hit = sql.toLowerCase(Locale.ROOT).contains(head.toLowerCase(Locale.ROOT))
                        && hasToken(tokens, head);
            }
            if (hit) {
                emit(tokens, sql, rule, result);
            }
        }
    }

    /** 函数族命中辅助：检查 SQL 文本是否包含「函数名(」调用形态（忽略大小写） */
    boolean containsFunctionCall(String sql, String fn) {
        String lower = sql.toLowerCase(Locale.ROOT);
        String pattern = fn.toLowerCase(Locale.ROOT) + "(";
        return lower.contains(pattern);
    }

    /** 供测试/诊断：打印某 SQL 的 token 列表 */
    List<String> tokensOf(String sql) {
        return normalizeTokens(sql);
    }

    // ============ 结构族 ============

    private void applyClauseRules(List<String> tokens, String sql, SqlDialect src, SqlDialect tgt,
                                  SqlScanResult result) {
        SqlCompatibilityRules.Rule[] rules = SqlCompatibilityRules.mergedClauseRules();
        if (rules == null) {
            return;
        }
        for (SqlCompatibilityRules.Rule rule : rules) {
            String elem = rule.element.toLowerCase(Locale.ROOT);
            if (sql.toLowerCase(Locale.ROOT).contains(elem)) {
                // 目标方言家族本身支持的同类结构不报（避免同族迁移误报噪音）
                if (elem.equals("rownum") && tgt.isOracleFamily()) {
                    continue; // 达梦/OceanBase Oracle 兼容模式支持 ROWNUM
                }
                if (elem.equals("limit") && tgt.isMySqlFamily()) {
                    continue; // MySQL/TiDB 原生支持 LIMIT
                }
                emit(tokens, sql, rule, result);
            }
        }
    }

    // ============ 操作符族 ============

    private void applyOperatorRules(List<String> tokens, String sql, SqlDialect src, SqlDialect tgt,
                                    SqlScanResult result) {
        SqlCompatibilityRules.Rule[] rules = SqlCompatibilityRules.mergedOperatorRules();
        for (SqlCompatibilityRules.Rule rule : rules) {
            String elem = rule.element;
            if (sql.toLowerCase(Locale.ROOT).contains(elem)) {
                // 排除目标方言自身支持的（如 || 在 PG 目标下不算问题）
                if (elem.equals("||") && tgt.isPgFamily()) {
                    continue;
                }
                if (elem.equals("regexp") && (tgt.isOracleFamily() || tgt.isMySqlFamily())) {
                    continue;
                }
                emit(tokens, sql, rule, result);
            }
        }
    }

    // ============ 类型族 ============

    private void applyTypeRules(List<String> tokens, String sql, SqlDialect src, SqlDialect tgt,
                                SqlScanResult result) {
        SqlCompatibilityRules.Rule[] rules = SqlCompatibilityRules.mergedTypeRules();
        for (SqlCompatibilityRules.Rule rule : rules) {
            String elem = rule.element.toLowerCase(Locale.ROOT);
            String lower = sql.toLowerCase(Locale.ROOT);
            // 命形态：独立词 / (type) / ,type / type, / type(长度) / 前缀
            if (containsTypeRef(lower, elem)) {
                // 目标方言自身支持的类型不报（如 MySQL 也有 BLOB / PG 有 BOOLEAN）
                if (elem.equals("blob") && tgt.isMySqlFamily()) {
                    continue;
                }
                if (elem.equals("boolean") && tgt.isPgFamily()) {
                    continue;
                }
                emit(tokens, sql, rule, result);
            }
        }
    }

    /** 类型引用命中：支持 type(n)/type 词边界形态 */
    private boolean containsTypeRef(String lower, String elem) {
        // 独立词 / (type) / ,type / type, / 开头 / type( 长度
        return lower.contains(" " + elem + " ")
                || lower.contains("(" + elem + ")")
                || lower.contains("," + elem + " ")
                || lower.contains(" " + elem + ",")
                || lower.startsWith(elem)
                || lower.contains(" " + elem + "(")
                || lower.contains("(" + elem + "(");
    }

    // ============ 降级：文本特征扫描 ============

    private void fallbackTextScan(String sql, SqlDialect src, SqlDialect tgt, SqlScanResult result) {
        // 解析失败不产生新问题（除结构族命中），仅做结构/函数兜底
        List<String> tokens = normalizeTokens(sql);
        applyClauseRules(tokens, sql, src, tgt, result);
        applyFunctionRules(tokens, sql, src, tgt, result);
        // Wave17：PL/SQL 对象兜底识别（解析失败时也能识别存储过程/函数/触发器/包/类型）
        applyPlsqlObjectRules(sql, src, tgt, result);
        // 提示：该条无法解析，需要人工复核
        result.addIssue(new SqlCompatibilityIssue(
                SqlCompatibilityIssue.LEVEL_WARN, "syntax", "sql-unparseable", "",
                sql, "该 SQL 无法被 SQL 解析器识别，可能包含目标方言不兼容语法或高级特性",
                "请将该 SQL 提取出来人工复核（可能是 PL/SQL 块、DDL 片段或方言专有语法）"));
    }

    // ============ Wave17：PL/SQL / 存储过程对象识别 ============

    private void applyPlsqlObjectRules(String sql, SqlDialect src, SqlDialect tgt, SqlScanResult result) {
        java.util.regex.Matcher m = P_PLSQL_OBJECT.matcher(sql);
        while (m.find()) {
            String kind = m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
            String name = m.group(2);
            String code = plsqlCodeFor(kind);
            String message = "检测到 " + m.group(1) + " 对象「" + name + "」：PL/SQL/存储过程层结构，目标方言不自动转换，需人工重写";
            String suggestion = "提取该 " + m.group(1) + " 体，人工重写为目标方言等价实现（或保留源端兼容模式）";
            emitPlsql(result, code, name, sql, message, suggestion);
        }
    }

    private static String plsqlCodeFor(String kind) {
        switch (kind) {
            case "PROCEDURE":
                return "SQL-P01";
            case "FUNCTION":
                return "SQL-P02";
            case "TRIGGER":
                return "SQL-P03";
            case "PACKAGE":
                return "SQL-P04";
            case "PACKAGE_BODY":
                return "SQL-P05";
            case "TYPE":
            case "TYPE_BODY":
                return "SQL-P06";
            default:
                return "SQL-P00";
        }
    }

    private void emitPlsql(SqlScanResult result, String code, String element, String sql,
                           String message, String suggestion) {
        for (SqlCompatibilityIssue existing : result.getIssues()) {
            if (existing.getCode().equals(code) && existing.getElement().equals(element)
                    && existing.getSql().equals(sql)) {
                return;
            }
        }
        result.addIssue(new SqlCompatibilityIssue(
                SqlCompatibilityIssue.LEVEL_ERROR, "plsql", code, element, sql, message, suggestion));
    }

    // ============ 工具 ============

    /** 家族 pair 键：把金仓/DM/OceanBase 折叠到 PG/Oracle 家族 */
    private String familyPairKey(SqlDialect src, SqlDialect tgt) {
        String s = familyName(src);
        String t = familyName(tgt);
        return s + "→" + t;
    }

    private String familyName(SqlDialect d) {
        if (d.isPgFamily()) {
            return "pg";
        }
        if (d.isOracleFamily()) {
            return "oracle";
        }
        if (d.isMySqlFamily()) {
            return "mysql";
        }
        return d.name().toLowerCase(Locale.ROOT);
    }

    private void emit(List<String> tokens, String sql, SqlCompatibilityRules.Rule rule, SqlScanResult result) {
        // 去重：同一 SQL 同一 code 只报一次
        for (SqlCompatibilityIssue existing : result.getIssues()) {
            if (existing.getCode().equals(rule.code) && existing.getSql().equals(sql)) {
                return;
            }
        }
        // 改写草稿：仅对 warn 级可机械映射项自动生成（error 留空需人工确认；info 无需）
        String rewritten = null;
        if (SqlCompatibilityIssue.LEVEL_WARN.equals(rule.level)) {
            rewritten = SqlRewriteDraft.draft(sql, result.getSourceDialect(), result.getTargetDialect());
            if (!SqlRewriteDraft.changed(sql, rewritten)) {
                rewritten = null;
            }
        }
        result.addIssue(new SqlCompatibilityIssue(
                rule.level, rule.category, rule.code, rule.element, sql, rule.message, rule.suggestion, rewritten));
    }
}
