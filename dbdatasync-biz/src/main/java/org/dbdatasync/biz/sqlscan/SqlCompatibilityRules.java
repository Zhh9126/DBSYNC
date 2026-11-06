package org.dbdatasync.biz.sqlscan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SQL 兼容度扫描规则表（纯离线数据底座）。
 *
 * <p>规则族分四类，命中后产出问题项：
 * <ul>
 *   <li>FUNCTION    —— 函数族（NVL/DECODE/TO_DATE/…）</li>
 *   <li>OPERATOR    —— 操作符族（|| 字符串连接 / := 赋值 / % 通配 / :: 类型转换）</li>
 *   <li>CLAUSE      —— 结构族（CONNECT BY / MINUS / ROWNUM / LIMIT / ON DUPLICATE KEY …）</li>
 *   <li>TYPE        —— 类型族（CLOB/BLOB/NUMBER/… 语义差异）</li>
 * </ul>
 * 每项规则：源方言家族 → 目标方言家族 → 元素 → 级别(error/warn/info) → 说明 + 改写建议。
 *
 * <p>不做自动转换，只输出「说明 + 建议」；建议为示意写法，需人工确认后落地。
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
public final class SqlCompatibilityRules {

    private SqlCompatibilityRules() {
    }

    /**
     * 单条规则。
     */
    public static final class Rule {
        public final String code;          // SQL-F01 等
        public final String category;      // function / operator / clause / type
        public final String element;       // 函数名 / 关键字 / 操作符 / 类型名（小写）
        public final String level;         // error / warn / info
        public final String message;       // 问题说明
        public final String suggestion;    // 改写建议

        Rule(String code, String category, String element, String level, String message, String suggestion) {
            this.code = code;
            this.category = category;
            this.element = element;
            this.level = level;
            this.message = message;
            this.suggestion = suggestion;
        }
    }

    // ============ 函数族规则（按 源家族→目标家族 → 元素 组织） ============

    /** Oracle 系 → MySQL 系 */
    private static final Map<String, Rule> ORACLE_TO_MYSQL_FUNCTIONS = map(
            r("SQL-F01", "function", "nvl", "warn", "NVL 是 Oracle 空值函数，目标 MySQL 无此函数",
                    "改写为 COALESCE(col, default)：COALESCE(expr, 0)"),
            r("SQL-F02", "function", "decode", "warn", "DECODE 是 Oracle 条件函数，目标 MySQL 不支持",
                    "改写为 CASE WHEN：CASE col WHEN a THEN x WHEN b THEN y ELSE z END"),
            r("SQL-F03", "function", "to_char", "warn", "TO_CHAR 日期格式化语义与 MySQL DATE_FORMAT 不同",
                    "改写为 DATE_FORMAT(col, '%Y-%m-%d %H:%i:%s')"),
            r("SQL-F04", "function", "to_date", "warn", "TO_DATE 字符串转日期，MySQL 用 STR_TO_DATE",
                    "改写为 STR_TO_DATE(str, '%Y-%m-%d')"),
            r("SQL-F05", "function", "to_number", "warn", "TO_NUMBER 转数值，MySQL 用 CAST",
                    "改写为 CAST(expr AS SIGNED) / CAST(expr AS DECIMAL(18,4))"),
            r("SQL-F08", "function", "sysdate", "warn", "SYSDATE 返回含时间，MySQL 用 NOW()",
                    "改写为 NOW()（若仅日期可用 CURDATE()）"),
            r("SQL-F09", "function", "trunc(日期)", "warn", "TRUNC 对日期截断，MySQL 用 DATE / DATE_FORMAT",
                    "截断到日：DATE(col)；截断到月：DATE_FORMAT(col, '%Y-%m-01')"),
            r("SQL-F10", "function", "add_months", "warn", "ADD_MONTHS 在 MySQL 无等价，用 DATE_ADD/间隔语法",
                    "改写为 DATE_ADD(col, INTERVAL n MONTH)"),
            r("SQL-F11", "function", "months_between", "warn", "MONTHS_BETWEEN 计算月份差，MySQL 无原生等价",
                    "改写为 PERIOD_DIFF(DATE_FORMAT(a,'%Y%m'), DATE_FORMAT(b,'%Y%m'))"),
            r("SQL-F12", "function", "instr", "warn", "INSTR 字符串位置查找，MySQL 用 LOCATE/INSTR（参数顺序不同）",
                    "改写为 LOCATE(substr, str)（注意参数顺序）"),
            r("SQL-F13", "function", "substr(第3参缺省)", "info", "Oracle SUBSTR 第 2 参可为负、第 3 参省略到末尾，语义略有差异",
                    "校验 SUBSTR 参数：MySQL SUBSTR 支持负位置，注意第 3 参缺省到末尾语义一致"),
            r("SQL-F14", "function", "listagg", "error", "LISTAGG 聚合字符串在 MySQL 无等价函数",
                    "改写为 GROUP_CONCAT(col SEPARATOR ',')（注意去重/排序差异）"),
            r("SQL-F15", "function", "wm_concat", "error", "WM_CONCAT 是 Oracle 非公开聚合串接，MySQL 不支持",
                    "改写为 GROUP_CONCAT(col SEPARATOR ',')"),
            r("SQL-F16", "function", "connect_by", "error", "CONNECT BY 层级查询是 Oracle 专有，MySQL 需递归 CTE 重写",
                    "改写为 WITH RECURSIVE cte AS (... UNION ALL SELECT ... FROM cte ...)（SQL 逻辑需人工重构）"),
            r("SQL-F18", "function", "nvl2", "warn", "NVL2 是 Oracle 三参空值函数，MySQL 无等价",
                    "改写为 CASE WHEN expr IS NULL THEN v2 ELSE v1 END"),
            r("SQL-F19", "function", "lpad", "warn", "LPAD 在 MySQL 存在但多字节字符行为有差异",
                    "改写为 LPAD(str, len, pad)（注意中文按字符计）"),
            r("SQL-F20", "function", "empty_clob/empty_blob", "error", "EMPTY_CLOB/EMPTY_BLOB 是 Oracle 专有初始化，MySQL 无",
                    "改写为空字符串 '' / 0x 空二进制字面量"),
            r("SQL-F21", "function", "nls_sort/utl_raw", "error", "NLS_* / UTL_* 函数族是 Oracle 专有包函数，目标无等价",
                    "需要业务逻辑改造，建议人工重写：/* 依赖 Oracle 专有包 */"),
            r("SQL-F77", "function", "regexp_like", "error", "REGEXP_LIKE 是 Oracle 正则匹配函数，MySQL 用 REGEXP 运算符",
                    "改写为 expr REGEXP 'pattern'（正则方言差异需校验）"),
            r("SQL-F78", "function", "regexp_replace", "warn", "REGEXP_REPLACE 三参版本 Oracle/MySQL 参数顺序不同",
                    "改写为 REGEXP_REPLACE(str, pattern, repl)（注意 from 参数语义）"),
            r("SQL-F79", "function", "regexp_substr", "error", "REGEXP_SUBSTR 提取子串，MySQL 无等价（8.0 起 REGEXP_SUBSTR 可用）",
                    "MySQL 8.0+ 可用 REGEXP_SUBSTR(str, pattern)；低版本需人工解析"),
            r("SQL-F80", "function", "ceil", "info", "CEIL 在 MySQL/PostgreSQL 均存在，语义一致",
                    "无需改造"),
            r("SQL-F81", "function", "floor", "info", "FLOOR 语义跨库一致",
                    "无需改造"));

    /** Oracle 系 → PG 系（金仓/PG） */
    private static final Map<String, Rule> ORACLE_TO_PG_FUNCTIONS = map(
            r("SQL-F01", "function", "nvl", "warn", "NVL 是 Oracle 空值函数，目标 PG/金仓无",
                    "改写为 COALESCE(expr, default)"),
            r("SQL-F02", "function", "decode", "warn", "DECODE 是 Oracle 条件函数，目标 PG/金仓支持（金仓兼容模式可用）但建议改写",
                    "改写为 CASE WHEN col THEN ... END（跨库可移植）"),
            r("SQL-F03", "function", "to_char", "info", "TO_CHAR 在 PG/金仓存在，但格式模型差异（FM/MM 等）",
                    "校验格式串：PG 用 to_char(dt, 'YYYY-MM-DD')，格式元素需核对"),
            r("SQL-F04", "function", "to_date", "info", "TO_DATE 在 PG/金仓存在，格式模型差异",
                    "改写或核对格式串：to_date(str, 'YYYY-MM-DD')"),
            r("SQL-F05", "function", "to_number", "info", "TO_NUMBER 在 PG 存在但格式语义不同",
                    "改写为 CAST(expr AS NUMERIC)"),
            r("SQL-F08", "function", "sysdate", "warn", "SYSDATE 在 PG/金仓无，用 now()",
                    "改写为 now()（金仓兼容模式可用 sysdate）"),
            r("SQL-F09", "function", "trunc(日期)", "info", "TRUNC 在 PG/金仓存在（date_trunc 或 trunc(numeric)），日期/数值语义分离",
                    "日期截断用 date_trunc('day', col)；数值截断用 trunc(col)"),
            r("SQL-F10", "function", "add_months", "warn", "ADD_MONTHS 在 PG/金仓无等价",
                    "改写为 (col + INTERVAL '1 month')（月末语义差异需注意）"),
            r("SQL-F11", "function", "months_between", "warn", "MONTHS_BETWEEN 在 PG/金仓无原生等价",
                    "改写为 EXTRACT(YEAR FROM age(a,b))*12 + EXTRACT(MONTH FROM age(a,b))"),
            r("SQL-F14", "function", "listagg", "warn", "LISTAGG 在 PG/金仓有等价 string_agg（金仓兼容模式可用）",
                    "改写为 string_agg(col, ',')（顺序用 ORDER BY 子句控制）"),
            r("SQL-F15", "function", "wm_concat", "error", "WM_CONCAT 是 Oracle 非公开函数，PG/金仓无",
                    "改写为 string_agg(col, ',')"),
            r("SQL-F16", "function", "connect_by", "warn", "CONNECT BY 层级查询，PG/金仓用递归 CTE",
                    "改写为 WITH RECURSIVE cte AS (SELECT ... UNION ALL SELECT ...)（逻辑重构）"),
            r("SQL-F18", "function", "nvl2", "warn", "NVL2 在 PG/金仓无等价",
                    "改写为 CASE WHEN expr IS NULL THEN v2 ELSE v1 END"),
            r("SQL-F20", "function", "empty_clob/empty_blob", "error", "EMPTY_CLOB/BLOB 是 Oracle 专有",
                    "改写为 '' / E'\\\\x' 空二进制"),
            r("SQL-F21", "function", "nls_sort/utl_raw", "error", "Oracle 专有包函数",
                    "人工重写（依赖 Oracle 专有包）"));

    /** MySQL 系 → PG 系 */
    private static final Map<String, Rule> MYSQL_TO_PG_FUNCTIONS = map(
            r("SQL-F31", "function", "ifnull", "warn", "IFNULL 是 MySQL 函数，PG/金仓无",
                    "改写为 COALESCE(expr, default)"),
            r("SQL-F32", "function", "if()", "warn", "IF(cond, a, b) 是 MySQL 条件函数，PG/金仓无",
                    "改写为 CASE WHEN cond THEN a ELSE b END"),
            r("SQL-F33", "function", "group_concat", "warn", "GROUP_CONCAT 在 PG/金仓用 string_agg",
                    "改写为 string_agg(col, ',')"),
            r("SQL-F34", "function", "date_format", "info", "DATE_FORMAT 格式语义与 PG to_char 不同",
                    "改写为 to_char(col, 'YYYY-MM-DD')（格式元素映射）"),
            r("SQL-F35", "function", "now()", "info", "NOW() 在 PG/金仓存在，语义一致",
                    "无需改造（PG 返回事务开始时间）"),
            r("SQL-F36", "function", "concat(多参)", "info", "CONCAT 多参在 PG/金仓：|| 或 concat() 均支持",
                    "改写为 concat(a, b) 或 a || b（注意 NULL 语义：|| 遇 NULL 输出已覆盖）"),
            r("SQL-F37", "function", "datediff", "warn", "DATEDIFF 在 PG/金仓无等价",
                    "改写为 (date_a - date_b)（返回整数天）"),
            r("SQL-F38", "function", "date_add", "warn", "DATE_ADD 在 PG/金仓无",
                    "改写为 col + INTERVAL '1 day'"),
            r("SQL-F39", "function", "unix_timestamp/from_unixtime", "info", "UNIX_TIMESTAMP/FROM_UNIXTIME 在 PG/金仓无等价",
                    "改写为 EXTRACT(EPOCH FROM col) / to_timestamp(sec)"),
            r("SQL-F40", "function", "find_in_set", "error", "FIND_IN_SET 是 MySQL 专有，PG/金仓无",
                    "改写为 strpos 或拆分成条件（POSITION(','||x||',' IN ','||set||',')）"),
            r("SQL-F41", "function", "last_insert_id", "error", "LAST_INSERT_ID 是 MySQL 会话函数，PG/金仓无",
                    "改写为 RETURNING 子句获取新主键"),
            r("SQL-F42", "function", "substring_index", "error", "SUBSTRING_INDEX 是 MySQL 专有，PG/金仓无",
                    "改写为 split_part(col, ',', n)"),
            r("SQL-F43", "function", "dayofweek/weekday", "warn", "DAYOFWEEK/WEEKDAY 索引语义与 PG 不同",
                    "改写为 EXTRACT(DOW FROM col)（注意 0=周日/1=周一差异）"),
            r("SQL-F44", "function", "curdate/current_timestamp", "info", "CURDATE/CURRENT_TIMESTAMP 语义在 PG 存在等价",
                    "改写为 CURRENT_DATE / CURRENT_TIMESTAMP"));

    /** MySQL 系 → Oracle 系 */
    private static final Map<String, Rule> MYSQL_TO_ORACLE_FUNCTIONS = map(
            r("SQL-F51", "function", "ifnull", "warn", "IFNULL 是 MySQL 函数，目标 Oracle/达梦无",
                    "改写为 NVL(expr, default)"),
            r("SQL-F52", "function", "if()", "warn", "IF() 条件函数目标 Oracle/达梦无",
                    "改写为 CASE WHEN cond THEN a ELSE b END"),
            r("SQL-F53", "function", "group_concat", "warn", "GROUP_CONCAT 目标 Oracle/达梦用 LISTAGG",
                    "改写为 LISTAGG(col, ',') WITHIN GROUP (ORDER BY col)"),
            r("SQL-F54", "function", "date_format", "warn", "DATE_FORMAT 目标 Oracle 用 TO_CHAR",
                    "改写为 TO_CHAR(col, 'YYYY-MM-DD')"),
            r("SQL-F55", "function", "now()", "info", "NOW() 目标 Oracle/达梦无",
                    "改写为 SYSDATE"),
            r("SQL-F56", "function", "limit", "warn", "LIMIT 目标 Oracle/达梦无（达梦兼容模式可用）",
                    "改写为 FETCH FIRST n ROWS ONLY 或 ROWNUM <= n"),
            r("SQL-F57", "function", "replace(多参)", "info", "REPLACE 多参目标 Oracle 存在且语义一致",
                    "无需改造"),
            r("SQL-F58", "function", "auto_increment", "warn", "AUTO_INCREMENT 是 MySQL 自增，目标 Oracle/达梦无",
                    "改写为 SEQUENCE + 触发器，或达梦 IDENTITY 列"),
            r("SQL-F59", "function", "on_duplicate_key", "warn", "ON DUPLICATE KEY UPDATE 是 MySQL 专有",
                    "改写为 MERGE INTO / 先查后改（Oracle 语义）"));

    /** PG 系 → MySQL 系 */
    private static final Map<String, Rule> PG_TO_MYSQL_FUNCTIONS = map(
            r("SQL-F61", "function", "string_agg", "warn", "STRING_AGG 是 PG 聚合，MySQL 用 GROUP_CONCAT",
                    "改写为 GROUP_CONCAT(col SEPARATOR ',')"),
            r("SQL-F62", "function", "now()::timestamp", "warn", ":: 类型转换语法 MySQL 不支持",
                    "改写为 CAST(expr AS ...)"),
            r("SQL-F63", "function", "ilike", "error", "ILIKE 是 PG 专有大小写不敏感匹配，MySQL 无",
                    "改写为 LOWER(col) LIKE LOWER(pattern)"),
            r("SQL-F64", "function", "||", "info", "|| 字符串连接在 MySQL（<8.0）默认是逻辑或",
                    "MySQL 8.0+ 设置 sql_mode 含 PIPES_AS_CONCAT；或改写为 CONCAT(a,b)"),
            r("SQL-F65", "function", "generate_series", "error", "GENERATE_SERIES 是 PG 表函数，MySQL 无",
                    "改写为递归 CTE / 临时数字表（WITH RECURSIVE seq AS (...)）"),
            r("SQL-F66", "function", "distinct on", "error", "DISTINCT ON 是 PG 专有，MySQL 无",
                    "改写为窗口函数 ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...) 过滤"),
            r("SQL-F67", "function", "array_agg", "error", "ARRAY_AGG 返回数组类型，MySQL 无数组类型",
                    "改写为 GROUP_CONCAT（注意语义差异）"),
            r("SQL-F68", "function", "regexp_replace(flags)", "warn", "REGEXP_REPLACE 参数/语法在 MySQL 有差异",
                    "改写为 REGEXP_REPLACE(str, pattern, repl)（核对参数顺序与转义）"),
            r("SQL-F69", "function", "date_part", "warn", "DATE_PART 在 MySQL 用 EXTRACT",
                    "改写为 EXTRACT(field FROM col)"),
            r("SQL-F70", "function", "to_char(数值)", "info", "TO_CHAR 数值格式化 MySQL 用 FORMAT/CAST",
                    "改写为 FORMAT(col, 2)（注意区域性差异）"));

    /** PG 系 → Oracle 系 */
    private static final Map<String, Rule> PG_TO_ORACLE_FUNCTIONS = map(
            r("SQL-F71", "function", "string_agg", "warn", "STRING_AGG 目标 Oracle/达梦用 LISTAGG",
                    "改写为 LISTAGG(col, ',') WITHIN GROUP (ORDER BY col)"),
            r("SQL-F72", "function", "ilike", "warn", "ILIKE 目标 Oracle/达梦无，Oracle 默认大小写敏感",
                    "改写为 LOWER(col) LIKE LOWER(pattern)"),
            r("SQL-F73", "function", "generate_series", "error", "GENERATE_SERIES 目标 Oracle/达梦无",
                    "改写为 CONNECT BY 层级生成行或临时数字表"),
            r("SQL-F74", "function", "distinct on", "error", "DISTINCT ON 目标 Oracle/达梦无",
                    "改写为 ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...) 过滤"),
            r("SQL-F75", "function", "::", "warn", ":: 类型转换目标 Oracle/达梦无",
                    "改写为 CAST(expr AS TYPE)"),
            r("SQL-F76", "function", "now()", "warn", "NOW() 目标 Oracle/达梦无",
                    "改写为 SYSDATE"));

    // ============ 操作符族 ============

    private static final Map<String, Rule> OPERATOR_RULES = map(
            r("SQL-O01", "operator", "||", "warn", "|| 字符串连接在目标方言语义可能不同（MySQL <8.0 为逻辑或）",
                    "改写为 CONCAT(a, b)（MySQL）或保留 ||（PG/达梦兼容）"),
            r("SQL-O02", "operator", ":=", "error", ":= 赋值运算符是 PL/SQL/T-SQL 专有，普通 SQL 不支持",
                    "从 SQL 中移除 PL 赋值语法（属存储过程层改造）"),
            r("SQL-O03", "operator", "<<>>", "warn", "位运算/移位在目标方言支持程度不同",
                    "核对位运算支持：MySQL 支持 & | ^ ~，PG 支持 & | # ~ << >>"),
            r("SQL-O04", "operator", "%", "info", "% 在目标方言含义不同（PG/% 取模，LIKE 通配）",
                    "核对上下文：取模用 MOD(a,b) 更可移植"),
            r("SQL-O05", "operator", "+", "info", "+ 在 Oracle 也是连接语法（旧式），目标方言不识别",
                    "核对 + 连接写法：Oracle 旧外连接 (+) 改为 LEFT/RIGHT JOIN"),
            r("SQL-O06", "operator", "@", "info", "@ 在 MySQL 是用户变量前缀，PG/Oracle 无",
                    "核对用户变量：MySQL @var 需改写为临时表/参数"),
            r("SQL-O07", "operator", "regexp", "warn", "REGEXP 运算符在 Oracle 语义不同（REGEXP_LIKE）",
                    "改写为 REGEXP_LIKE(expr, pattern)（Oracle）" ),
            r("SQL-O08", "operator", "div", "warn", "DIV 整数除法是 MySQL 专有运算符",
                    "改写为 CAST(a / b AS SIGNED)（PG）或 FLOOR(a / b)（Oracle）"),
            r("SQL-O09", "operator", "->>", "warn", "->> JSON 操作符是 PG/MySQL 8 各有差异",
                    "核对 JSON 提取语义：PG 用 ->'key' / ->>'key'；MySQL 用 -> / ->>（JSON_UNQUOTE 差异）"));

    // ============ 结构族 ============

    private static final Map<String, Rule> CLAUSE_RULES = map(
            r("SQL-C01", "clause", "connect by", "error", "CONNECT BY 层级查询，目标方言需递归 CTE 重写",
                    "改写为 WITH RECURSIVE cte AS (SELECT ... UNION ALL SELECT ...)（MySQL/PG）"),
            r("SQL-C02", "clause", "start with", "error", "START WITH 与 CONNECT BY 配套，目标方言无",
                    "并入递归 CTE 初始集（ONLY 行）"),
            r("SQL-C03", "clause", "minus", "error", "MINUS 集合差运算目标 MySQL 无",
                    "改写为 NOT IN / NOT EXISTS / LEFT JOIN ... WHERE b IS NULL"),
            r("SQL-C04", "clause", "intersect", "warn", "INTERSECT 在 MySQL 无（PG/达梦有）",
                    "MySQL 改写为 IN 子查询或 JOIN ... WHERE 匹配"),
            r("SQL-C05", "clause", "rownum", "warn", "ROWNUM 取前 N 行伪列，目标方言无",
                    "改写为 LIMIT n（MySQL/PG）或 FETCH FIRST n ROWS ONLY"),
            r("SQL-C06", "clause", "limit", "info", "LIMIT 在 Oracle/达梦目标无（达梦兼容模式可用）",
                    "改写为 FETCH FIRST n ROWS ONLY（Oracle 12c+ / 达梦）"),
            r("SQL-C07", "clause", "on duplicate key", "warn", "ON DUPLICATE KEY UPDATE 是 MySQL 专有",
                    "改写为 INSERT ... ON CONFLICT DO UPDATE（PG）或 MERGE（Oracle/达梦）"),
            r("SQL-C08", "clause", "on conflict", "warn", "ON CONFLICT 是 PG 专有",
                    "改写为 INSERT ... ON DUPLICATE KEY UPDATE（MySQL）或 MERGE（Oracle/达梦）"),
            r("SQL-C09", "clause", "limit offset", "info", "LIMIT/OFFSET 分页在 Oracle 无等价",
                    "改写为 OFFSET n ROWS FETCH NEXT m ROWS ONLY（Oracle 12c+）"),
            r("SQL-C10", "clause", "with recursive", "info", "WITH RECURSIVE 在 Oracle 用 WITH ... CONNECT BY 或递归子因子",
                    "核对目标方言递归 CTE 支持（MySQL 8+/PG/Oracle 11g2+）"),
            r("SQL-C11", "clause", "full join", "warn", "FULL JOIN 在 MySQL 无（Oracle/PG 有）",
                    "改写为 LEFT JOIN UNION RIGHT JOIN"),
            r("SQL-C12", "clause", "using", "info", "USING(列) 在 Oracle/PG/MySQL 均支持，达梦需核对",
                    "核对目标方言 USING 支持（达梦部分版本不支持）"),
            r("SQL-C13", "clause", "wmsys.wm_concat", "error", "WM_CONCAT 是 Oracle 专有聚合",
                    "改写为 LISTAGG / GROUP_CONCAT / string_agg"),
            r("SQL-C14", "clause", "dual", "info", "SELECT ... FROM DUAL 在 MySQL/PG 可省略或存在等价",
                    "MySQL 可省略 FROM；PG 用 SELECT 表达式"),
            r("SQL-C15", "clause", "hint", "warn", "Oracle HINT 语法（/*+ */）目标方言不识别",
                    "移除或改写为 target 对应 hint（达梦部分兼容 /*+ */）"),
            r("SQL-C16", "clause", "pivot", "error", "Oracle PIVOT 行转列目标 MySQL 无原生",
                    "改写为条件聚合 SUM(CASE WHEN ...)（PG 需安装扩展或用 crosstab）"),
            r("SQL-C17", "clause", "unpivot", "error", "UNPIVOT 列转行目标 MySQL 无原生",
                    "改写为 UNION ALL 多段 SELECT（每列一段）"),
            r("SQL-C18", "clause", "merge", "warn", "MERGE INTO 是 Oracle/达梦专有，目标 MySQL 无",
                    "改写为 INSERT ... ON DUPLICATE KEY UPDATE / ON CONFLICT（按主键）"),
            r("SQL-C19", "clause", "bulk collect", "error", "BULK COLLECT 是 PL/SQL 批量采集语法",
                    "属存储过程层，SQL 扫描仅提示，需 PL/SQL 转换器"),
            r("SQL-C20", "clause", "for update", "info", "FOR UPDATE 行锁在多数目标支持，语义需核对",
                    "核对目标锁语义（MySQL/PG/Oracle 均支持）"),
            r("SQL-C21", "clause", "mvcc/nowait", "info", "NOWAIT/SKIP LOCKED 支持程度不同",
                    "核对目标方言：PG/Oracle 支持 SKIP LOCKED，MySQL 不支持 SKIP LOCKED"));

    // ============ 类型族 ============

    private static final Map<String, Rule> TYPE_RULES = map(
            r("SQL-T01", "type", "number", "warn", "NUMBER 在目标 MySQL 无等价类型",
                    "按精度映射：NUMBER → DECIMAL(p,s) / INT / BIGINT"),
            r("SQL-T02", "type", "varchar2", "warn", "VARCHAR2 是 Oracle 专有，目标无",
                    "改写为 VARCHAR(n)（PG/MySQL）"),
            r("SQL-T03", "type", "clob", "warn", "CLOB 在目标 MySQL 用 LONGTEXT / PG 用 TEXT",
                    "改写为 LONGTEXT（MySQL）/ TEXT（PG）"),
            r("SQL-T04", "type", "blob", "info", "BLOB 几乎所有目标都支持，注意大小写",
                    "目标保留 BLOB（PG 用 BYTEA）"),
            r("SQL-T05", "type", "raw", "warn", "RAW 是 Oracle 专有二进制，目标 MySQL 用 VARBINARY / PG 用 BYTEA",
                    "改写为 VARBINARY(n)（MySQL）/ BYTEA（PG）"),
            r("SQL-T06", "type", "timestamp(6)", "info", "TIMESTAMP(6) 精度各目标支持程度不同",
                    "核对目标微秒精度（MySQL 5.6.4+ 支持 6 位）"),
            r("SQL-T07", "type", "date", "info", "DATE 语义差异：Oracle DATE 含时间、MySQL DATE 仅日期",
                    "核对目标 DATE 语义，必要时改 TIMESTAMP"),
            r("SQL-T08", "type", "boolean", "warn", "BOOLEAN 支持差异：MySQL tinyint(1)、PG real boolean、Oracle 无",
                    "Oracle 改写为 NUMBER(1) / CHAR(1)('Y'/'N')"),
            r("SQL-T09", "type", "double", "info", "DOUBLE 语义差异（DOUBLE PRECISION）",
                    "核对精度映射：PG double precision / Oracle BINARY_DOUBLE"),
            r("SQL-T10", "type", "nvarchar2", "warn", "NVARCHAR2 是 Oracle 专有，目标无",
                    "改写为 VARCHAR(n)（按目标字符集核对长度）或 NCHAR"),
            r("SQL-T11", "type", "binary_double", "warn", "BINARY_DOUBLE 是 Oracle 专有",
                    "改写为 DOUBLE（MySQL）/ double precision（PG）"),
            r("SQL-T12", "type", "rowid", "error", "ROWID 是 Oracle 物理行地址伪列，目标无",
                    "改写为主键/唯一键定位行"),
            r("SQL-T13", "type", "urowid", "error", "UROWID 是 Oracle 逻辑行地址，目标无",
                    "改写为主键/唯一键定位行"),
            r("SQL-T14", "type", "identity", "info", "IDENTITY 列各目标支持程度不同（PG GENERATED / MySQL AUTO_INCREMENT）",
                    "核对自增实现：PG GENERATED ALWAYS AS IDENTITY / MySQL AUTO_INCREMENT"));

    // ============ 规则组织 ============

    private static final Map<String, Rule[]> FUNCTION_TABLE = new LinkedHashMap<>();

    static {
        put(FUNCTION_TABLE, "oracle→mysql", ORACLE_TO_MYSQL_FUNCTIONS);
        put(FUNCTION_TABLE, "oracle→pg", ORACLE_TO_PG_FUNCTIONS);
        put(FUNCTION_TABLE, "mysql→pg", MYSQL_TO_PG_FUNCTIONS);
        put(FUNCTION_TABLE, "mysql→oracle", MYSQL_TO_ORACLE_FUNCTIONS);
        put(FUNCTION_TABLE, "pg→mysql", PG_TO_MYSQL_FUNCTIONS);
        put(FUNCTION_TABLE, "pg→oracle", PG_TO_ORACLE_FUNCTIONS);
        // 同家族（同构）不报函数差异，或仅 info
    }

    /** 操作符族全表（source 与 target 无关的语义差异项） */
    private static final Rule[] OPERATOR_RULES_ALL = OPERATOR_RULES.values().toArray(new Rule[0]);

    /** 结构族全表 */
    private static final Rule[] CLAUSE_RULES_ALL = CLAUSE_RULES.values().toArray(new Rule[0]);

    /** 类型族全表 */
    private static final Rule[] TYPE_RULES_ALL = TYPE_RULES.values().toArray(new Rule[0]);

    private static Map<String, Rule> map(Rule... rules) {
        Map<String, Rule> m = new LinkedHashMap<>();
        for (Rule r : rules) {
            m.put(r.category + ":" + r.element, r);
        }
        return m;
    }

    private static Rule r(String code, String category, String element, String level,
                          String message, String suggestion) {
        return new Rule(code, category, element, level, message, suggestion);
    }

    private static void put(Map<String, Rule[]> table, String key, Map<String, Rule> rules) {
        table.put(key, rules.values().toArray(new Rule[0]));
    }

    /** 按函数族 pair 键取函数规则数组 */
    public static Rule[] getFunctions(String pairKey) {
        return FUNCTION_TABLE.get(pairKey);
    }

    /** 操作符族全表（与 pair 无关） */
    public static Rule[] allOperatorRules() {
        return OPERATOR_RULES_ALL;
    }

    /** 结构族全表 */
    public static Rule[] allClauseRules() {
        return CLAUSE_RULES_ALL;
    }

    /** 类型族全表 */
    public static Rule[] allTypeRules() {
        return TYPE_RULES_ALL;
    }

    // ============ 自定义规则合并（无自定义时返回预置数组同一引用，零拷贝零副作用） ============

    /** 预置函数规则 + 自定义函数规则（按 pair 限定） */
    public static Rule[] mergedFunctions(String pairKey) {
        return merge(getFunctions(pairKey), SqlCompatibilityRuleLoader.customFunctions(pairKey));
    }

    /** 预置操作符规则 + 自定义操作符规则 */
    public static Rule[] mergedOperatorRules() {
        return merge(allOperatorRules(), SqlCompatibilityRuleLoader.customOperatorRules());
    }

    /** 预置结构规则 + 自定义结构规则 */
    public static Rule[] mergedClauseRules() {
        return merge(allClauseRules(), SqlCompatibilityRuleLoader.customClauseRules());
    }

    /** 预置类型规则 + 自定义类型规则（DdlTypeMapping 仍只读 allTypeRules()，不受影响） */
    public static Rule[] mergedTypeRules() {
        return merge(allTypeRules(), SqlCompatibilityRuleLoader.customTypeRules());
    }

    /** 无自定义规则时直接返回 base（同一引用）；否则 copyOf 追加，绝不改动 base 与其顺序 */
    private static Rule[] merge(Rule[] base, Rule[] custom) {
        if (custom == null || custom.length == 0) {
            return base;
        }
        Rule[] b = base == null ? new Rule[0] : base;
        Rule[] merged = java.util.Arrays.copyOf(b, b.length + custom.length);
        System.arraycopy(custom, 0, merged, b.length, custom.length);
        return merged;
    }
}
