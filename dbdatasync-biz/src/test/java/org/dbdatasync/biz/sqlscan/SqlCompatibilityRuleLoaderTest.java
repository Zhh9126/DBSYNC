package org.dbdatasync.biz.sqlscan;

import org.dbdatasync.biz.sqlscan.SqlCompatibilityRules.Rule;
import org.dbdatasync.biz.vo.SqlPrecheckReportVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Wave11：自定义 SQL 扫描规则加载器 + SqlPrecheckService 两处可读提示单测。
 *
 * <p>纯临时文件/系统属性，{@code @Before}/{@code @After} 清理属性与缓存，绝不污染其它测试。
 * 覆盖：未配置/文件不存在/解析失败降级、properties 分流、pair 限定、非法规则跳过、
 * element 归一、merged 零副作用（assertSame）、append 不改预置数组、默认扫描结果一致，
 * 以及两个 QA 观察项。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class SqlCompatibilityRuleLoaderTest {

    private final List<Path> tempFiles = new ArrayList<>();

    @Before
    public void setUp() {
        System.clearProperty(SqlCompatibilityRuleLoader.PROPERTY);
        SqlCompatibilityRuleLoader.resetForTest();
    }

    @After
    public void tearDown() {
        System.clearProperty(SqlCompatibilityRuleLoader.PROPERTY);
        SqlCompatibilityRuleLoader.resetForTest();
        for (Path p : tempFiles) {
            deleteRecursively(p);
        }
        tempFiles.clear();
    }

    // ① 未配置属性 → 4 个 customXxx 全为空数组
    @Test
    public void noProperty_allCustomEmpty() {
        assertEquals(0, SqlCompatibilityRuleLoader.customFunctions("oracle→mysql").length);
        assertEquals(0, SqlCompatibilityRuleLoader.customOperatorRules().length);
        assertEquals(0, SqlCompatibilityRuleLoader.customClauseRules().length);
        assertEquals(0, SqlCompatibilityRuleLoader.customTypeRules().length);
        assertTrue(SqlCompatibilityRuleLoader.isLoaded());
    }

    // ② 文件不存在 → 空数组且无异常
    @Test
    public void fileNotFound_returnsEmptyNoException() {
        System.setProperty(SqlCompatibilityRuleLoader.PROPERTY, "wave11-definitely-not-exists.properties");
        SqlCompatibilityRuleLoader.resetForTest();
        assertEquals(0, SqlCompatibilityRuleLoader.customFunctions("oracle→mysql").length);
        assertEquals(0, SqlCompatibilityRuleLoader.customOperatorRules().length);
        assertEquals(0, SqlCompatibilityRuleLoader.customClauseRules().length);
        assertEquals(0, SqlCompatibilityRuleLoader.customTypeRules().length);
    }

    // ③ 非法 JSON / 非法 Properties → 空数组且无异常
    @Test
    public void invalidJsonAndProperties_returnsEmptyNoException() throws Exception {
        Path badJson = writeTemp(".json", "{ this is not json ");
        use(badJson);
        assertEquals(0, SqlCompatibilityRuleLoader.customOperatorRules().length);
        assertEquals(0, SqlCompatibilityRuleLoader.customClauseRules().length);

        // 非法的 Unicode 转义会让 Properties.load(Reader) 抛 IllegalArgumentException → 整体降级为空
        Path badProps = writeTemp(".properties", "custom.1.code=\\uZZZZ\n");
        use(badProps);
        assertEquals(0, SqlCompatibilityRuleLoader.customTypeRules().length);
    }

    // ④ properties 合法加载并按 category 分流（含 UTF-8 中文 message 读取）
    @Test
    public void validProperties_splitByCategory() throws Exception {
        String props = String.join("\n",
                "custom.1.code=SQL-X01",
                "custom.1.category=function",
                "custom.1.element=my_fn",
                "custom.1.level=warn",
                "custom.1.message=自定义函数说明",
                "custom.1.suggestion=s1",
                "custom.2.code=SQL-X02",
                "custom.2.category=operator",
                "custom.2.element=##",
                "custom.2.level=error",
                "custom.2.message=m2",
                "custom.2.suggestion=s2",
                "custom.3.code=SQL-X03",
                "custom.3.category=clause",
                "custom.3.element=my_clause",
                "custom.3.level=info",
                "custom.3.message=m3",
                "custom.3.suggestion=s3",
                "custom.4.code=SQL-X04",
                "custom.4.category=type",
                "custom.4.element=my_type",
                "custom.4.level=warn",
                "custom.4.message=m4",
                "custom.4.suggestion=s4");
        use(writeTemp(".properties", props));
        assertEquals(1, SqlCompatibilityRuleLoader.customOperatorRules().length);
        assertEquals(1, SqlCompatibilityRuleLoader.customClauseRules().length);
        assertEquals(1, SqlCompatibilityRuleLoader.customTypeRules().length);
        // 无 pair 的 function 规则对所有函数族全局生效
        Rule[] fns = SqlCompatibilityRuleLoader.customFunctions("oracle→mysql");
        assertEquals(1, fns.length);
        assertEquals("自定义函数说明", fns[0].message); // UTF-8 中文未被 ISO-8859-1 破坏
    }

    // ⑤ pair 限定只进对应函数族；不填 pair 为全局
    @Test
    public void pairLimited_onlyEntersMatchingFunctionFamily() throws Exception {
        String props = String.join("\n",
                "custom.1.code=SQL-XF1",
                "custom.1.category=function",
                "custom.1.element=fn_a",
                "custom.1.level=warn",
                "custom.1.message=ma",
                "custom.1.suggestion=sa",
                "custom.1.pair=oracle→mysql",
                "custom.2.code=SQL-XF2",
                "custom.2.category=function",
                "custom.2.element=fn_b",
                "custom.2.level=info",
                "custom.2.message=mb",
                "custom.2.suggestion=sb");
        use(writeTemp(".properties", props));
        // oracle→mysql：限定的 + 全局的，共 2 条
        assertEquals(2, SqlCompatibilityRuleLoader.customFunctions("oracle→mysql").length);
        // 其它 pair：仅全局那条
        Rule[] others = SqlCompatibilityRuleLoader.customFunctions("pg→mysql");
        assertEquals(1, others.length);
        assertEquals("SQL-XF2", others[0].code);
    }

    // ⑥ level 非法 / 字段缺失 / category 非法 → 跳过该条，其余保留
    @Test
    public void invalidLevelOrMissingFields_skippedOthersKept() throws Exception {
        String props = String.join("\n",
                "custom.1.code=SQL-XA",
                "custom.1.category=type",
                "custom.1.element=ta",
                "custom.1.level=WARN", // 大写 level 应归一为小写
                "custom.1.message=ma",
                "custom.1.suggestion=sa",
                "custom.2.code=SQL-XB",
                "custom.2.category=type",
                "custom.2.element=tb",
                "custom.2.level=fatal", // level 非法 → 跳过
                "custom.2.message=mb",
                "custom.2.suggestion=sb",
                "custom.3.code=SQL-XC",
                "custom.3.category=type",
                "custom.3.element=tc",
                "custom.3.level=info",
                "custom.3.message=mc", // 缺 suggestion → 跳过
                "custom.4.code=SQL-XD",
                "custom.4.category=unknown", // category 非法 → 跳过
                "custom.4.element=td",
                "custom.4.level=warn",
                "custom.4.message=md",
                "custom.4.suggestion=sd");
        use(writeTemp(".properties", props));
        Rule[] types = SqlCompatibilityRuleLoader.customTypeRules();
        assertEquals(1, types.length);
        assertEquals("SQL-XA", types[0].code);
        assertEquals("warn", types[0].level);
    }

    // ⑦ element 大小写归一
    @Test
    public void elementNormalizedToLowerCase() throws Exception {
        String props = String.join("\n",
                "custom.1.code=SQL-XE",
                "custom.1.category=operator",
                "custom.1.element=My_Mixed_OP",
                "custom.1.level=warn",
                "custom.1.message=me",
                "custom.1.suggestion=se");
        use(writeTemp(".properties", props));
        Rule[] ops = SqlCompatibilityRuleLoader.customOperatorRules();
        assertEquals(1, ops.length);
        assertEquals("my_mixed_op", ops[0].element);
    }

    // ⑧ 无自定义时 mergedXxx 返回与 allXxxRules()/getFunctions() 完全相同的数组引用（零副作用核心证明）
    @Test
    public void mergedWithoutCustom_returnsSameArrayReference() {
        assertSame(SqlCompatibilityRules.allOperatorRules(), SqlCompatibilityRules.mergedOperatorRules());
        assertSame(SqlCompatibilityRules.allClauseRules(), SqlCompatibilityRules.mergedClauseRules());
        assertSame(SqlCompatibilityRules.allTypeRules(), SqlCompatibilityRules.mergedTypeRules());
        String pair = "oracle→mysql";
        assertSame(SqlCompatibilityRules.getFunctions(pair), SqlCompatibilityRules.mergedFunctions(pair));
    }

    // ⑨ 有自定义时 merged 长度 = base + custom，前 N 个与预置一致且预置数组未被改动
    @Test
    public void mergedWithCustom_appendsAndBaseUnchanged() throws Exception {
        String pair = "oracle→mysql";
        Rule[] baseFn = SqlCompatibilityRules.getFunctions(pair);
        int baseFnLen = baseFn.length;
        Rule[] baseOp = SqlCompatibilityRules.allOperatorRules();
        int baseOpLen = baseOp.length;

        String props = String.join("\n",
                "custom.1.code=SQL-XF",
                "custom.1.category=function",
                "custom.1.element=fn_x",
                "custom.1.level=warn",
                "custom.1.message=mf",
                "custom.1.suggestion=sf",
                "custom.2.code=SQL-XO",
                "custom.2.category=operator",
                "custom.2.element=op_x",
                "custom.2.level=info",
                "custom.2.message=mo",
                "custom.2.suggestion=so");
        use(writeTemp(".properties", props));

        Rule[] mergedFn = SqlCompatibilityRules.mergedFunctions(pair);
        assertEquals(baseFnLen + 1, mergedFn.length);
        for (int i = 0; i < baseFnLen; i++) {
            assertSame(baseFn[i], mergedFn[i]);
        }
        assertEquals("SQL-XF", mergedFn[baseFnLen].code);
        assertEquals(baseFnLen, SqlCompatibilityRules.getFunctions(pair).length);

        Rule[] mergedOp = SqlCompatibilityRules.mergedOperatorRules();
        assertEquals(baseOpLen + 1, mergedOp.length);
        for (int i = 0; i < baseOpLen; i++) {
            assertSame(baseOp[i], mergedOp[i]);
        }
        assertEquals("SQL-XO", mergedOp[baseOpLen].code);
        assertEquals(baseOpLen, SqlCompatibilityRules.allOperatorRules().length);
    }

    // ⑩ 默认无属性时跑一条 Oracle→MySQL 样例 SQL，扫描结果与未启用一致
    @Test
    public void defaultNoProperty_scanMatchesBaseline() {
        String sql = "SELECT NVL(a.amount, 0), DECODE(a.status, 1, 'Y', 'N') FROM t WHERE ROWNUM <= 10";
        SqlCompatibilityScanner scanner = new SqlCompatibilityScanner();
        SqlScanResult baseline = scanner.scan(sql, "Oracle", "MySQL");
        List<String> codes = codes(baseline);
        assertFalse(codes.isEmpty());
        assertTrue(codes.contains("SQL-F01"));
        assertTrue(codes.contains("SQL-F02"));
        assertEquals(baseline.getIssues().size(),
                baseline.getErrorCount() + baseline.getWarnCount() + baseline.getInfoCount());
        for (String c : codes) {
            assertFalse(c.startsWith("SQL-X")); // 不含任何自定义规则
        }

        // 配置一个不存在的文件（仍仅预置规则）→ 扫描结果与基线完全一致
        System.setProperty(SqlCompatibilityRuleLoader.PROPERTY, "wave11-no-such-file.properties");
        SqlCompatibilityRuleLoader.resetForTest();
        SqlScanResult after = scanner.scan(sql, "Oracle", "MySQL");
        assertEquals(codes, codes(after));
        assertEquals(baseline.getErrorCount(), after.getErrorCount());
        assertEquals(baseline.getWarnCount(), after.getWarnCount());
        assertEquals(baseline.getInfoCount(), after.getInfoCount());
        assertEquals(baseline.getIssues().size(), after.getIssues().size());
    }

    // ⑪ QA 观察项：目录内无 .sql 时 warning 含「未找到 .sql 文件」，且兜底文案更准确、不中断
    @Test
    public void emptyDirectory_warnsNoSqlFiles() throws Exception {
        Path dir = Files.createTempDirectory("wave11-precheck-empty-");
        tempFiles.add(dir);
        Files.write(dir.resolve("readme.txt"), "not sql".getBytes(StandardCharsets.UTF_8));

        SqlPrecheckService service = new SqlPrecheckService();
        SqlPrecheckReportVO report = service.precheck("", dir.toString(), "Oracle", "MySQL");
        assertTrue(report.getInputSummary().contains("未找到 .sql 文件"));
        // 观察项 3：用户填了目录但无 .sql 时，措辞不再误导为「未输入」
        assertTrue(report.getInputSummary().contains("未获取到可执行扫描的 SQL 内容"));
        assertNull(report.getResult());
        assertEquals("none", report.getInputMode());
        assertFalse(report.isBlocking());
    }

    // ⑫ QA 观察项：含一个 >1MB 文件时 warning 含「超过单文件」且不中断其它输入
    @Test
    public void largeFileSkipped_warnsAndKeepsOtherInput() throws Exception {
        Path dir = Files.createTempDirectory("wave11-precheck-large-");
        tempFiles.add(dir);
        byte[] big = new byte[(int) SqlPrecheckService.MAX_FILE_BYTES + 64];
        Arrays.fill(big, (byte) 'a');
        Files.write(dir.resolve("a_big.sql"), big);
        Files.write(dir.resolve("b_ok.sql"),
                "SELECT NVL(a, 0) FROM t;".getBytes(StandardCharsets.UTF_8));

        SqlPrecheckService service = new SqlPrecheckService();
        SqlPrecheckReportVO report = service.precheck("", dir.toString(), "Oracle", "MySQL");
        assertTrue(report.getInputSummary().contains("超过单文件"));
        assertNotNull(report.getResult()); // 其它输入仍被扫描，未中断
        assertTrue(report.getResult().isScanned());
        assertTrue(hasCode(report.getResult(), "SQL-F01"));
        assertFalse(report.isBlocking());
    }

    // ============ 工具 ============

    private Path writeTemp(String suffix, String content) throws IOException {
        Path p = Files.createTempFile("wave11-rules-", suffix);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        tempFiles.add(p);
        return p;
    }

    private void use(Path p) {
        System.setProperty(SqlCompatibilityRuleLoader.PROPERTY, p.toString());
        SqlCompatibilityRuleLoader.resetForTest();
    }

    private List<String> codes(SqlScanResult result) {
        List<String> out = new ArrayList<>();
        for (SqlCompatibilityIssue issue : result.getIssues()) {
            out.add(issue.getCode());
        }
        return out;
    }

    private boolean hasCode(SqlScanResult result, String code) {
        for (SqlCompatibilityIssue issue : result.getIssues()) {
            if (code.equals(issue.getCode())) {
                return true;
            }
        }
        return false;
    }

    private void deleteRecursively(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.sorted(Comparator.reverseOrder()).forEach(x -> {
                        try {
                            Files.deleteIfExists(x);
                        } catch (IOException ignore) {
                            // 清理失败不影响断言
                        }
                    });
                }
            } else {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignore) {
            // 清理失败不影响断言
        }
    }
}
