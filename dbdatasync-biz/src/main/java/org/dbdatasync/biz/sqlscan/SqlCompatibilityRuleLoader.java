package org.dbdatasync.biz.sqlscan;

import org.dbdatasync.biz.sqlscan.SqlCompatibilityRules.Rule;
import org.dbdatasync.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 自定义 SQL 兼容度扫描规则加载器（纯静态、无 Spring/容器依赖、线程安全懒加载）。
 *
 * <p>通过系统属性 {@link #PROPERTY} 指定一个 classpath 资源路径（{@code .json} 或
 * {@code .properties}）；未配置、文件不存在、解析失败或无任何合法规则时一律降级为
 * 「仅预置规则」，4 个 customXxx 查询返回空数组，绝不返回 {@code null}、绝不抛异常。
 * 加载失败/文件不存在时记 warn/error，但不半加载、不向上抛出。</p>
 *
 * <p>格式：
 * <ul>
 *   <li>{@code .json}：JSON 数组，元素含 code/category/element/level/message/suggestion
 *       （function 可额外含 pair）。</li>
 *   <li>其它（按 {@code .properties} 处理）：{@code custom.<序号>.code/category/element/
 *       level/message/suggestion}，function 可额外含 {@code custom.<序号>.pair}；序号任意。</li>
 * </ul>
 * 单条规则：必填字段任一空白、level 非 error/warn/info、category 非四类之一 → 跳过该条并 warn；
 * element 统一 trim 后小写，level 归一为小写。</p>
 *
 * <p><strong>默认未配置系统属性时零副作用</strong>：不读 classpath、不改动预置规则表，
 * {@code mergedXxx} 直接返回预置数组同一引用；预置规则不变则扫描结果与 Wave5/Wave8 完全一致
 * （issue 顺序 / 分级计数 / warn 改写草稿均不变）。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public final class SqlCompatibilityRuleLoader {

    /** 系统属性键：值为 classpath 资源路径（.properties 或 .json），也兼容文件系统路径 */
    public static final String PROPERTY = "dbdatasync.sqlscan.custom.rules";

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlCompatibilityRuleLoader.class);

    private static final Rule[] EMPTY = new Rule[0];

    /** 懒加载标志（volatile + synchronized 双重检查，保证幂等与可见性） */
    private static volatile boolean loaded = false;

    /** 加载结果缓存；仅在 loaded=true 后可读，永不为 null */
    private static volatile RuleSet cache = null;

    private SqlCompatibilityRuleLoader() {
    }

    /** 自定义函数族规则（pair 为空表示全局；否则仅并入指定 pair 的函数族） */
    public static Rule[] customFunctions(String pairKey) {
        RuleSet set = ensureLoaded();
        if (set == null || set.functions.length == 0) {
            return EMPTY;
        }
        String key = pairKey == null ? null : pairKey.trim().toLowerCase(Locale.ROOT);
        List<Rule> matched = new ArrayList<>();
        for (FnRule fn : set.functions) {
            if (fn.pair == null || fn.pair.isEmpty() || fn.pair.equals(key)) {
                matched.add(fn.rule);
            }
        }
        return matched.toArray(new Rule[0]);
    }

    public static Rule[] customOperatorRules() {
        RuleSet set = ensureLoaded();
        return set == null ? EMPTY : set.operators;
    }

    public static Rule[] customClauseRules() {
        RuleSet set = ensureLoaded();
        return set == null ? EMPTY : set.clauses;
    }

    public static Rule[] customTypeRules() {
        RuleSet set = ensureLoaded();
        return set == null ? EMPTY : set.types;
    }

    /** 仅供测试：清空加载标志与缓存，使下一次查询按当前系统属性重新加载 */
    public static void resetForTest() {
        synchronized (SqlCompatibilityRuleLoader.class) {
            cache = null;
            loaded = false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    private static RuleSet ensureLoaded() {
        if (!loaded) {
            synchronized (SqlCompatibilityRuleLoader.class) {
                if (!loaded) {
                    // 先写 cache 再写 loaded（volatile 写顺序保证可见性）
                    cache = load();
                    loaded = true;
                }
            }
        }
        return cache;
    }

    /** 完整加载；任何失败都降级为空规则集，绝不半加载、绝不抛出 */
    private static RuleSet load() {
        String path = System.getProperty(PROPERTY);
        if (isBlank(path)) {
            return RuleSet.empty();
        }
        path = path.trim();
        InputStream in = null;
        try {
            in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
            if (in == null) {
                // classpath 未命中时回退到文件系统路径（便于 -D 指向绝对路径 / 单测临时文件）
                Path p = Paths.get(path);
                if (Files.isRegularFile(p)) {
                    in = Files.newInputStream(p);
                }
            }
            if (in == null) {
                LOGGER.warn("自定义 SQL 扫描规则文件未找到（classpath/文件系统）：{}，降级为仅预置规则", path);
                return RuleSet.empty();
            }
            String text = readAll(in);
            List<RawRule> raw = path.toLowerCase(Locale.ROOT).endsWith(".json")
                    ? parseJson(text) : parseProperties(text);
            return validate(raw);
        } catch (Exception e) {
            LOGGER.error("加载自定义 SQL 扫描规则失败：{}，降级为仅预置规则", path, e);
            return RuleSet.empty();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignore) {
                    // 关闭失败不影响降级结果
                }
            }
        }
    }

    /** Java 8：把流按 UTF-8 读成字符串（不使用 Java 9+ 的 InputStream.readAllBytes） */
    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        // 兼容带 BOM 的 UTF-8 文件：去掉开头 BOM，避免首条 properties key 被污染
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private static List<RawRule> parseJson(String text) {
        List<RawRule> out = new ArrayList<>();
        List<?> list = JsonUtil.parseList(text);
        if (list == null) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) item;
            RawRule rr = new RawRule();
            rr.code = str(m.get("code"));
            rr.category = str(m.get("category"));
            rr.element = str(m.get("element"));
            rr.level = str(m.get("level"));
            rr.message = str(m.get("message"));
            rr.suggestion = str(m.get("suggestion"));
            rr.pair = str(m.get("pair"));
            out.add(rr);
        }
        return out;
    }

    private static List<RawRule> parseProperties(String text) throws IOException {
        Properties props = new Properties();
        // 用 Reader 载入 UTF-8 文本，避免 Properties.load(InputStream) 的 ISO-8859-1 导致中文乱码
        props.load(new StringReader(text));
        Map<String, RawRule> groups = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("custom.")) {
                continue;
            }
            String rest = key.substring("custom.".length());
            int dot = rest.lastIndexOf('.');
            if (dot <= 0 || dot == rest.length() - 1) {
                continue;
            }
            String id = rest.substring(0, dot);
            String field = rest.substring(dot + 1).toLowerCase(Locale.ROOT);
            RawRule rr = groups.get(id);
            if (rr == null) {
                rr = new RawRule();
                groups.put(id, rr);
            }
            rr.set(field, props.getProperty(key));
        }
        return new ArrayList<>(groups.values());
    }

    /** 单条校验：任一必填空白 / level 非法 / category 非法 → 跳过该条并 warn，其余保留 */
    private static RuleSet validate(List<RawRule> raws) {
        List<Rule> operators = new ArrayList<>();
        List<Rule> clauses = new ArrayList<>();
        List<Rule> types = new ArrayList<>();
        List<FnRule> functions = new ArrayList<>();
        for (RawRule rr : raws) {
            String code = trim(rr.code);
            String category = trim(rr.category);
            String element = trim(rr.element);
            String level = trim(rr.level);
            String message = trim(rr.message);
            String suggestion = trim(rr.suggestion);
            if (isBlank(code) || isBlank(category) || isBlank(element)
                    || isBlank(message) || isBlank(suggestion)) {
                LOGGER.warn("跳过自定义 SQL 扫描规则（必填字段为空）：code={}", code);
                continue;
            }
            String lv = level.toLowerCase(Locale.ROOT);
            if (!"error".equals(lv) && !"warn".equals(lv) && !"info".equals(lv)) {
                LOGGER.warn("跳过自定义 SQL 扫描规则（level 非法）：code={}, level={}", code, level);
                continue;
            }
            String cat = category.toLowerCase(Locale.ROOT);
            if (!"function".equals(cat) && !"operator".equals(cat)
                    && !"clause".equals(cat) && !"type".equals(cat)) {
                LOGGER.warn("跳过自定义 SQL 扫描规则（category 非法）：code={}, category={}", code, category);
                continue;
            }
            String elem = element.toLowerCase(Locale.ROOT);
            Rule rule = new SqlCompatibilityRules.Rule(code, cat, elem, lv, message, suggestion);
            String pair = trim(rr.pair);
            if (pair != null) {
                pair = pair.toLowerCase(Locale.ROOT);
            }
            if ("function".equals(cat)) {
                functions.add(new FnRule(rule, pair));
            } else if ("operator".equals(cat)) {
                operators.add(rule);
            } else if ("clause".equals(cat)) {
                clauses.add(rule);
            } else {
                types.add(rule);
            }
        }
        return new RuleSet(operators, clauses, types, functions);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 待校验的原始规则（字段可 null） */
    private static final class RawRule {
        private String code;
        private String category;
        private String element;
        private String level;
        private String message;
        private String suggestion;
        private String pair;

        private void set(String field, String value) {
            switch (field) {
                case "code":
                    code = value;
                    break;
                case "category":
                    category = value;
                    break;
                case "element":
                    element = value;
                    break;
                case "level":
                    level = value;
                    break;
                case "message":
                    message = value;
                    break;
                case "suggestion":
                    suggestion = value;
                    break;
                case "pair":
                    pair = value;
                    break;
                default:
                    break;
            }
        }
    }

    private static final class FnRule {
        private final Rule rule;
        private final String pair; // null/空 = 全局

        private FnRule(Rule rule, String pair) {
            this.rule = rule;
            this.pair = pair;
        }
    }

    /** 校验后的缓存（数组固定，查询直接返回引用，merged 用 copyOf 不改动它） */
    private static final class RuleSet {
        private final Rule[] operators;
        private final Rule[] clauses;
        private final Rule[] types;
        private final FnRule[] functions;

        private RuleSet(List<Rule> operators, List<Rule> clauses, List<Rule> types, List<FnRule> functions) {
            this.operators = operators.toArray(new Rule[0]);
            this.clauses = clauses.toArray(new Rule[0]);
            this.types = types.toArray(new Rule[0]);
            this.functions = functions.toArray(new FnRule[0]);
        }

        private static RuleSet empty() {
            return new RuleSet(new ArrayList<Rule>(), new ArrayList<Rule>(),
                    new ArrayList<Rule>(), new ArrayList<FnRule>());
        }
    }
}
