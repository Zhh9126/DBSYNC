package org.dbdatasync.biz.qa;

import org.dbdatasync.biz.BizException;
import org.dbdatasync.biz.impl.DatabaseSyncServiceImpl;
import org.dbdatasync.biz.service.impl.DatabaseSyncPerfAdviceServiceImpl;
import org.dbdatasync.biz.sqlscan.DdlDraft;
import org.dbdatasync.biz.sqlscan.DdlDraftGenerator;
import org.dbdatasync.biz.sqlscan.SqlCompatibilityScanner;
import org.dbdatasync.biz.sqlscan.SqlPrecheckService;
import org.dbdatasync.biz.sqlscan.SqlScanResult;
import org.dbdatasync.biz.vo.DatabaseMappingVO;
import org.dbdatasync.biz.vo.DatabaseSyncPerfAdviceVO;
import org.dbdatasync.biz.vo.SqlPrecheckReportVO;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.TaskDetailProfile;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.model.TableMapping;
import org.dbdatasync.sdk.spi.TaskService;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Wave10 QA 独立边界 / 负向回归用例（严过关，Yan）。
 *
 * <p>刻意不复用工程师既有测试的数据与断言，聚焦 PRD/设计验收点之外的负向与边界路径：
 * <ol>
 *   <li>perfAdvice：Meta.total 与 成功+失败 不一致时的核对提示与「Meta 不被改写」</li>
 *   <li>perfAdvice：负耗时 / successRows=0 不除零；单条明细 JSON 解析失败逐表跳过不 NPE</li>
 *   <li>映射归一化：根字符串化数组 / tableMappings 字符串化数组 / null 元素 / 畸形 JSON 不抛 syntax error</li>
 *   <li>映射归一化：多库多表 index 重排后 getSortedTableMappings() 顺序与配置一致</li>
 *   <li>DDL 草稿：大小写混合 / schema 前缀 / 多类型混排；error+warn 混合按「error 不生成」判定</li>
 *   <li>DDL 草稿：无命中返回原 SQL 且 changed=false、不含注释头</li>
 *   <li>预检：目录不存在 / 路径是文件 / 系统路径 / 无 *.sql / 单文件超 1MB 的可读提示与不中断</li>
 *   <li>预检：文本+目录合并计数、未知方言 info 降级、未输入扫描器调用次数为 0</li>
 * </ol>
 *
 * <p>JUnit 4；纯 JDK 动态代理桩 + 反射调用私有方法 + Files.createTempDirectory 临时目录；
 * 不连库、不启服务、不写真实数据、不改动任何生产源码。</p>
 *
 * @author QA 严过关
 * @version 1.0.0
 * @date 2026-11-06
 */
public class Wave10QaEdgeCaseTest {

    private final DatabaseSyncServiceImpl mappingService = new DatabaseSyncServiceImpl();
    private final DdlDraftGenerator ddlGenerator = new DdlDraftGenerator();

    // ==================== 用例 1：perfAdvice Meta 不一致 ====================

    @Test
    public void perfAdvice_metaTotalMismatch_addsCheckHintAndNeverMutatesMeta() {
        // total=9999 与 success(120)+fail(30)=150 明显不一致
        Meta meta = meta(9999L, 120L, 30L, 1000L, 3000L);
        DatabaseSyncPerfAdviceServiceImpl service =
                newPerfService(meta, new ArrayList<TableGroup>(), new ArrayList<Map<String, Object>>());
        DatabaseSyncPerfAdviceVO vo = service.analyze("qa-edge-task-1");

        assertEquals(120L, vo.getSuccessRows());
        assertEquals(30L, vo.getFailRows());
        assertEquals(150L, vo.getTotalRows());       // 恒等：totalRows = success+fail，不取 Meta.total
        assertEquals(9999L, vo.getMetaTotal());     // metaTotal 仅作核对字段
        assertFalse(vo.isRowsConsistent());
        assertTrue(vo.getConclusion().contains("请核对运行记录"));
        assertFalse(vo.getConclusion().contains("数据总量与差异均为 0"));

        // 关键边界：服务只读 Meta，绝不改写 Meta 对象本身
        assertEquals(9999L, meta.getTotal().get());
        assertEquals(120L, meta.getSuccess().get());
        assertEquals(30L, meta.getFail().get());
        assertEquals(1000L, meta.getBeginTime());
        assertEquals(3000L, meta.getEndTime());
    }

    // ==================== 用例 2：perfAdvice 负耗时 / 明细坏 JSON ====================

    @Test
    public void perfAdvice_negativeDurationAndZeroSuccess_noDivideByZero() {
        // endTime < beginTime → durationMs = max(0, -4000) = 0，吞吐 0
        Meta m1 = meta(0L, 0L, 0L, 9000L, 5000L);
        DatabaseSyncPerfAdviceVO vo1 = newPerfService(
                m1, new ArrayList<TableGroup>(), new ArrayList<Map<String, Object>>())
                .analyze("qa-edge-task-2a");
        assertEquals(0L, vo1.getDurationMs());
        assertEquals(0.0D, vo1.getThroughputPerSecond(), 0.0001D);
        assertNotNull(vo1.getConclusion());

        // durationMs>0 但 successRows=0 → 分子只用 successRows，吞吐仍 0
        Meta m2 = meta(0L, 0L, 7L, 1000L, 3000L);
        DatabaseSyncPerfAdviceVO vo2 = newPerfService(
                m2, new ArrayList<TableGroup>(), new ArrayList<Map<String, Object>>())
                .analyze("qa-edge-task-2b");
        assertEquals(2000L, vo2.getDurationMs());
        assertEquals(0L, vo2.getSuccessRows());
        assertEquals(7L, vo2.getFailRows());
        assertEquals(7L, vo2.getTotalRows());
        assertEquals(0.0D, vo2.getThroughputPerSecond(), 0.0001D);
        assertNotNull(vo2.getConclusion());
    }

    @Test
    public void perfAdvice_brokenDetailJson_skippedWithoutNpe() {
        List<TableGroup> groups = new ArrayList<>();
        groups.add(group("T_OK"));
        groups.add(group("T_BROKEN"));
        Collection<Map<String, Object>> rows = new ArrayList<>();
        rows.add(detailRow("T_OK",
                "{\"sourceTotal\":1500000,\"targetTotal\":1500000,\"diffTotal\":0,\"failTotal\":0}"));
        rows.add(detailRow("T_BROKEN", "{not-json-at-all"));   // 单表解析失败
        Meta m = meta(1500000L, 1500000L, 0L, 0L, 1000L);

        DatabaseSyncPerfAdviceVO vo = newPerfService(m, groups, rows).analyze("qa-edge-task-2c");
        assertEquals(2, vo.getTableCount());                  // 坏表未被丢弃，整体不 NPE
        assertNotNull(vo.getConclusion());
        assertEquals(1, vo.getBigTables().size());            // 坏表 sourceTotal=0 不进大表
        assertEquals("T_OK", vo.getBigTables().get(0).getTableName());
        assertEquals(1_500_000L, vo.getBigTables().get(0).getSourceRows()); // 精确取明细 sourceTotal
        assertEquals(0, vo.getDiffTables().size());
    }

    // ==================== 用例 3：映射归一化负向形态 ====================

    @Test
    public void mapping_stringifiedRootAndTableMappingsAndNullElement_neverSyntaxError() {
        // ① 根为「字符串化的数组」（整个值是带外层引号的 JSON 字符串）→ 设计 ③ 降级 emptyList
        String stringifiedRoot =
                "\"[{\\\"index\\\":1,\\\"sourceDatabase\\\":\\\"SR\\\",\\\"tableMappings\\\":[]}]\"";
        assertTrue(parse(stringifiedRoot).isEmpty());

        // ② 根为空白字符串 / 带引号空格字符串
        assertTrue(parse("   ").isEmpty());
        assertTrue(parse("\" \"").isEmpty());

        // ③ tableMappings 为字符串化数组（与工程师用例不同数据）
        String stringifiedTm = "[{\"index\":1,\"sourceDatabase\":\"TM\",\"tableMappings\":"
                + "\"[{\\\"index\\\":3,\\\"sourceTable\\\":\\\"X\\\",\\\"targetTable\\\":\\\"TX\\\"}]\"}]";
        List<DatabaseMappingVO> l3 = parse(stringifiedTm);
        assertEquals(1, l3.size());
        assertEquals("TM", l3.get(0).getSourceDatabase());
        assertEquals(1, l3.get(0).getSortedTableMappings().size());
        assertEquals("X", l3.get(0).getSortedTableMappings().get(0).getSourceTable());
        assertEquals("TX", l3.get(0).getSortedTableMappings().get(0).getTargetTable());

        // ④ 数组内 null 元素 + 正常元素：null 被剔除，正常元素保留
        String withNull = "[null,{\"index\":1,\"sourceDatabase\":\"DB\",\"tableMappings\":[]}]";
        List<DatabaseMappingVO> l4 = parse(withNull);
        assertEquals(1, l4.size());
        assertEquals("DB", l4.get(0).getSourceDatabase());
        assertTrue(l4.get(0).getSortedTableMappings().isEmpty());
    }

    @Test
    public void mapping_malformedJson_throwsReadableBizExceptionWithoutSyntaxError() {
        String[] malformed = {
                "[{\"index\":1,\"tableMappings\":[{\"sourceTable\":\"A\"}]",  // 数组不闭合
                "[{\"index\":1,\"tableMappings\":[",                          // 中途截断
                "{\"index\":1,\"sourceDatabase\":\"X\""                      // 根对象残缺
        };
        for (String json : malformed) {
            try {
                parse(json);
                fail("畸形输入应抛可读 BizException: " + json);
            } catch (BizException e) {
                assertNotNull(e.getMessage());
                assertFalse(e.getMessage().toLowerCase(Locale.ROOT).contains("syntax error"));
                assertFalse(e.getMessage().contains("offset"));
                assertTrue(e.getMessage().contains("库映射格式错误"));
            }
        }
    }

    // ==================== 用例 4：多库多表 index 重排顺序 ====================

    @Test
    public void mapping_multiDbIndexReorder_sortedOrderMatchesConfig() {
        String json = "["
                + "{\"index\":3,\"sourceDatabase\":\"DB_C\",\"tableMappings\":["
                + "{\"index\":3,\"sourceTable\":\"C3\",\"targetTable\":\"TC3\"},"
                + "{\"index\":1,\"sourceTable\":\"C1\",\"targetTable\":\"TC1\"},"
                + "{\"index\":2,\"sourceTable\":\"C2\",\"targetTable\":\"TC2\"}]},"
                + "{\"index\":1,\"sourceDatabase\":\"DB_A\",\"tableMappings\":["
                + "{\"index\":2,\"sourceTable\":\"A2\",\"targetTable\":\"TA2\"},"
                + "{\"index\":1,\"sourceTable\":\"A1\",\"targetTable\":\"TA1\"}]},"
                + "{\"index\":2,\"sourceDatabase\":\"DB_B\",\"tableMappings\":[]}"
                + "]";
        List<DatabaseMappingVO> list = parse(json);
        assertEquals(3, list.size());
        sort(list);  // 既有 normalizeAndSortMappings()

        // 库按 index 重排：DB_A(1) / DB_B(2) / DB_C(3)，且 index 重置为 1,2,3
        assertEquals("DB_A", list.get(0).getSourceDatabase());
        assertEquals("DB_B", list.get(1).getSourceDatabase());
        assertEquals("DB_C", list.get(2).getSourceDatabase());
        assertEquals(1, list.get(0).getIndex());
        assertEquals(2, list.get(1).getIndex());
        assertEquals(3, list.get(2).getIndex());

        // 表按 index 升序，顺序与「按 index 排序后的配置」一致
        List<TableMapping> a = list.get(0).getSortedTableMappings();
        assertEquals(2, a.size());
        assertEquals("A1", a.get(0).getSourceTable());
        assertEquals("TA1", a.get(0).getTargetTable());
        assertEquals("A2", a.get(1).getSourceTable());
        assertEquals("TA2", a.get(1).getTargetTable());

        List<TableMapping> c = list.get(2).getSortedTableMappings();
        assertEquals(3, c.size());
        assertEquals("C1", c.get(0).getSourceTable());
        assertEquals("C2", c.get(1).getSourceTable());
        assertEquals("C3", c.get(2).getSourceTable());
        assertEquals("TC3", c.get(2).getTargetTable());

        assertTrue(list.get(1).getSortedTableMappings().isEmpty());
    }

    // ==================== 用例 5：DDL 大小写/前缀/混排、error+warn ====================

    @Test
    public void ddl_mixedCaseSchemaPrefixAndMultiType_allMapped() {
        String ddl = "CREATE TABLE APP.\"T_ORDER\" ("
                + " ID number(10,2), NAME varchar2(64), NICK NVARCHAR2(32),"
                + " MEMO ClOb, FLAG boolean, RATE Number, ADDR Varchar2 )";
        DdlDraft draft = ddlGenerator.generate(ddl, "Oracle", "MySQL");
        assertTrue(draft.isChanged());
        assertEquals(org.dbdatasync.biz.sqlscan.SqlCompatibilityIssue.LEVEL_WARN, draft.getLevel());
        String s = draft.getDraftDdl();
        assertTrue(s.startsWith(DdlDraftGenerator.DRAFT_HEADER));
        assertTrue(s.contains("DECIMAL(10,2)"));     // NUMBER(p,s)
        assertTrue(s.contains("DECIMAL(18,4)"));    // 裸 NUMBER
        assertTrue(s.contains("VARCHAR(64)"));      // VARCHAR2(n)
        assertTrue(s.contains("VARCHAR(32)"));      // NVARCHAR2(n)
        assertTrue(s.contains("VARCHAR(255)"));     // 裸 VARCHAR2 默认长度
        assertTrue(s.contains("TEXT"));             // CLOB
        assertTrue(s.contains("TINYINT(1)"));       // BOOLEAN
        assertFalse(s.contains("VARCHAR2"));
        assertFalse(s.toUpperCase(Locale.ROOT).contains("NUMBER"));
        assertTrue(s.contains("APP.\"T_ORDER\""));  // schema 前缀与引号表名原样保留
        assertTrue(draft.getNote().contains("需人工确认"));
    }

    @Test
    public void ddl_errorPlusWarn_noDraftGenerated() {
        // error 级类型与多个 warn 级类型混排：按设计「error 不生成草稿」优先
        String ddl = "CREATE TABLE t (rid ROWID, name VARCHAR2(64), memo CLOB, flag BOOLEAN)";
        DdlDraft draft = ddlGenerator.generate(ddl, "Oracle", "MySQL");
        assertFalse(draft.isChanged());
        assertEquals(org.dbdatasync.biz.sqlscan.SqlCompatibilityIssue.LEVEL_ERROR, draft.getLevel());
        assertEquals("", draft.getDraftDdl());
        assertFalse(draft.getDraftDdl().contains(DdlDraftGenerator.DRAFT_HEADER));
        assertTrue(draft.getNote().contains("需人工重写"));
        // 即便命中了 warn 级类型，草稿仍不生成
        assertTrue(draft.getMappedTypes().contains("VARCHAR2"));

        DdlDraft urowid = ddlGenerator.generate(
                "CREATE TABLE t (u UROWID, a NUMBER(8))", "Oracle", "MySQL");
        assertFalse(urowid.isChanged());
        assertEquals(org.dbdatasync.biz.sqlscan.SqlCompatibilityIssue.LEVEL_ERROR, urowid.getLevel());
        assertEquals("", urowid.getDraftDdl());
    }

    // ==================== 用例 6：DDL 无命中返回原 SQL ====================

    @Test
    public void ddl_noMappingHit_returnsOriginalSqlUnchanged() {
        String ddl = "CREATE TABLE qa_plain (id INT, name VARCHAR(64), created_at DATE, note VARCHAR(255))";
        DdlDraft draft = ddlGenerator.generate(ddl, "Oracle", "MySQL");
        assertFalse(draft.isChanged());
        assertEquals(org.dbdatasync.biz.sqlscan.SqlCompatibilityIssue.LEVEL_INFO, draft.getLevel());
        assertEquals(ddl, draft.getDraftDdl());
        assertEquals(ddl, draft.getOriginalDdl());
        assertFalse(draft.getDraftDdl().contains(DdlDraftGenerator.DRAFT_HEADER));
        assertTrue(draft.getNote().contains("无映射命中"));
        assertTrue(draft.getMappedTypes().isEmpty());
    }

    // ==================== 用例 7：预检目录边界 ====================

    @Test
    public void precheck_directoryEdgeCases_readableHintsAndNoAbort() throws Exception {
        CountingScanner sc = new CountingScanner();
        SqlPrecheckService service = serviceWith(sc);
        Path base = Files.createTempDirectory("wave10-qa-dir-");
        try {
            // ① 目录不存在
            SqlPrecheckReportVO r1 = service.precheck(
                    "", base.resolve("nope").toString(), "Oracle", "MySQL");
            assertTrue(r1.getInputSummary().contains("目录不存在或不可读"));
            assertNull(r1.getResult());

            // ② 路径是文件而非目录
            Path file = base.resolve("plain.sql");
            Files.write(file, "SELECT 1;".getBytes(StandardCharsets.UTF_8));
            SqlPrecheckReportVO r2 = service.precheck("", file.toString(), "Oracle", "MySQL");
            assertTrue(r2.getInputSummary().contains("目录不存在或不可读"));
            assertNull(r2.getResult());

            // ③ 系统路径（用户家目录本身）被拒
            String home = System.getProperty("user.home");
            SqlPrecheckReportVO r3 = service.precheck("", home, "Oracle", "MySQL");
            assertTrue(r3.getInputSummary().contains("不允许扫描"));
            assertNull(r3.getResult());
            assertEquals(0, sc.calls);   // 以上三种硬越界均不触发扫描器

            // ④ 目录内无 *.sql：不中断同时输入的 SQL 文本
            Path emptyDir = Files.createTempDirectory("wave10-qa-empty-");
            try {
                SqlPrecheckReportVO r4 = service.precheck(
                        "SELECT NVL(a,0) FROM t;", emptyDir.toString(), "Oracle", "MySQL");
                assertEquals("text", r4.getInputMode());
                assertNotNull(r4.getResult());
                assertTrue(r4.getResult().isScanned());
                assertTrue(r4.getResult().getStatementCount() >= 1);
            } finally {
                deleteRecursively(emptyDir);
            }

            // ⑤ 单文件 >1MB 被跳过，其它小文件仍扫描，不中断其它输入
            Path bigDir = Files.createTempDirectory("wave10-qa-big-");
            try {
                Files.write(bigDir.resolve("a_small.sql"),
                        "SELECT NVL(a,0) FROM t;".getBytes(StandardCharsets.UTF_8));
                byte[] huge = new byte[1024 * 1024 + 64];
                Arrays.fill(huge, (byte) ' ');
                Files.write(bigDir.resolve("z_huge.sql"), huge);
                SqlPrecheckReportVO r5 = service.precheck("", bigDir.toString(), "Oracle", "MySQL");
                assertEquals("directory", r5.getInputMode());
                assertNotNull(r5.getResult());
                assertNotNull(r5.getInputSummary());
                assertTrue(r5.getResult().getStatementCount() >= 1);
                assertTrue(hasCode(r5.getResult(), "SQL-F01"));  // 小文件内容仍被扫描
                assertFalse(r5.isBlocking());
            } finally {
                deleteRecursively(bigDir);
            }
        } finally {
            deleteRecursively(base);
        }
    }

    // ==================== 用例 8：预检合并 / 方言降级 / 零副作用 ====================

    @Test
    public void precheck_textAndDirectoryMerged_statementCountCorrect() throws Exception {
        Path dir = Files.createTempDirectory("wave10-qa-merge-");
        try {
            Files.write(dir.resolve("b_one.sql"),
                    "SELECT SYSDATE FROM dual;".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("c_two.sql"),
                    "SELECT DECODE(x,1,'Y','N') FROM t;".getBytes(StandardCharsets.UTF_8));
            CountingScanner sc = new CountingScanner();
            SqlPrecheckService service = serviceWith(sc);
            SqlPrecheckReportVO report = service.precheck(
                    "SELECT NVL(a,0) FROM t; SELECT ROWNUM FROM t WHERE ROWNUM <= 5;",
                    dir.toString(), "Oracle", "MySQL");

            assertEquals("mixed", report.getInputMode());
            assertEquals(1, sc.calls);                       // 合并后只调用一次扫描器
            assertFalse(report.isBlocking());                // 默认仅提示不阻断
            assertEquals(SqlPrecheckService.HINT, report.getHint());
            assertNotNull(report.getResult());
            assertTrue(report.getResult().getStatementCount() >= 3);
            assertTrue(hasCode(report.getResult(), "SQL-F01"));   // 来源：文本 NVL
            assertTrue(hasCode(report.getResult(), "SQL-F02"));   // 来源：目录 DECODE
            assertTrue(report.getInputSummary().contains("2 个 .sql 文件"));
            // 口径对齐 SqlScanResult：分级计数之和 == issues 数
            assertEquals(report.getResult().getErrorCount() + report.getResult().getWarnCount()
                    + report.getResult().getInfoCount(), report.getResult().getIssues().size());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void precheck_unknownDialect_degradesToInfo() {
        CountingScanner sc = new CountingScanner();
        SqlPrecheckService service = serviceWith(sc);
        SqlPrecheckReportVO report = service.precheck(
                "SELECT NVL(a,0) FROM t;", "", "Hive", "MySQL");
        assertNotNull(report.getResult());
        assertFalse(report.getResult().isScanned());
        assertTrue(hasCode(report.getResult(), "sql-unsupported-dialect"));
        assertEquals(1, report.getResult().getInfoCount());
        assertFalse(report.isBlocking());
        assertEquals(1, sc.calls);
    }

    @Test
    public void precheck_noInput_scannerNeverCalled() {
        CountingScanner sc = new CountingScanner();
        SqlPrecheckService service = serviceWith(sc);
        SqlPrecheckReportVO r1 = service.precheck("", "", "Oracle", "MySQL");
        assertEquals(0, sc.calls);
        assertEquals("none", r1.getInputMode());
        assertNull(r1.getResult());
        assertTrue(r1.getInputSummary().contains("未输入"));
        assertFalse(r1.isBlocking());

        SqlPrecheckReportVO r2 = service.precheck(null, null, null, null);
        assertEquals(0, sc.calls);
        assertNull(r2.getResult());

        SqlPrecheckReportVO r3 = service.precheck("   ", "   ", "Oracle", "MySQL");
        assertEquals(0, sc.calls);
        assertNull(r3.getResult());
    }

    // ==================== 桩 / 工具 ====================

    /** 计数扫描器：用于断言「未输入时不调用扫描器」与「合并输入只调用一次」 */
    private static final class CountingScanner extends SqlCompatibilityScanner {
        private int calls = 0;

        @Override
        public SqlScanResult scan(String sql, String sourceDialect, String targetDialect) {
            calls++;
            return super.scan(sql, sourceDialect, targetDialect);
        }
    }

    private SqlPrecheckService serviceWith(SqlCompatibilityScanner scanner) {
        SqlPrecheckService service = new SqlPrecheckService();
        service.setScanner(scanner);
        return service;
    }

    private DatabaseSyncPerfAdviceServiceImpl newPerfService(
            final Meta meta,
            final List<TableGroup> groups,
            final Collection<Map<String, Object>> detailRows) {
        DatabaseSyncPerfAdviceServiceImpl service = new DatabaseSyncPerfAdviceServiceImpl();
        inject(service, "taskService", proxy(TaskService.class, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if ("get".equals(m.getName())) {
                    DatabaseSyncTask t = new DatabaseSyncTask();
                    t.setName("QA 边界用例任务");
                    return t;
                }
                return defaultValue(m.getReturnType());
            }
        }));
        inject(service, "metaProfile", proxy(MetaProfile.class, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if ("getMetaByTaskId".equals(m.getName())) {
                    return meta;
                }
                return defaultValue(m.getReturnType());
            }
        }));
        inject(service, "tableGroupProfile", proxy(TableGroupProfile.class, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if ("pageScanTableGroups".equals(m.getName())) {
                    @SuppressWarnings("unchecked")
                    Consumer<List<TableGroup>> consumer = (Consumer<List<TableGroup>>) a[2];
                    consumer.accept(groups);
                    return null;
                }
                return defaultValue(m.getReturnType());
            }
        }));
        inject(service, "taskDetailProfile", proxy(TaskDetailProfile.class, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if ("queryResults".equals(m.getName())) {
                    Paging<Map<String, Object>> paging = new Paging<>(1, 100000);
                    paging.setData(detailRows);
                    return paging;
                }
                return defaultValue(m.getReturnType());
            }
        }));
        return service;
    }

    private static Object proxy(Class<?> iface, InvocationHandler handler) {
        return Proxy.newProxyInstance(
                Wave10QaEdgeCaseTest.class.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }

    private static void inject(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("注入桩失败: " + fieldName, e);
        }
    }

    private static Meta meta(long total, long success, long fail, long begin, long end) {
        Meta m = new Meta();
        m.setTotal(new AtomicLong(total));
        m.setSuccess(new AtomicLong(success));
        m.setFail(new AtomicLong(fail));
        m.setBeginTime(begin);
        m.setEndTime(end);
        return m;
    }

    private static TableGroup group(String targetTableName) {
        Table table = new Table();
        table.setName(targetTableName);
        TableGroup g = new TableGroup();
        g.setTargetTable(table);
        return g;
    }

    private static Map<String, Object> detailRow(String targetTable, String dataJson) {
        Map<String, Object> row = new HashMap<>();
        row.put("targetTable", targetTable);
        row.put("data", dataJson);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<DatabaseMappingVO> parse(String json) {
        try {
            Method method = DatabaseSyncServiceImpl.class
                    .getDeclaredMethod("parseDatabaseMappings", String.class);
            method.setAccessible(true);
            return (List<DatabaseMappingVO>) method.invoke(mappingService, json);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sort(List<DatabaseMappingVO> mappings) {
        try {
            Method method = DatabaseSyncServiceImpl.class
                    .getDeclaredMethod("normalizeAndSortMappings", List.class);
            method.setAccessible(true);
            method.invoke(mappingService, mappings);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean hasCode(SqlScanResult result, String code) {
        for (org.dbdatasync.biz.sqlscan.SqlCompatibilityIssue issue : result.getIssues()) {
            if (code.equals(issue.getCode())) {
                return true;
            }
        }
        return false;
    }

    private void deleteRecursively(Path dir) throws java.io.IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignore) {
                    // 清理失败不影响断言
                }
            });
        }
    }
}
