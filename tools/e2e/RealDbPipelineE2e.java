import org.dbdatasync.biz.cutover.CutoverExecutor;
import org.dbdatasync.biz.cutover.CutoverPhase;
import org.dbdatasync.biz.cutover.DefaultCutoverExecutor;
import org.dbdatasync.connector.h2.cdc.H2TriggerLogListener;
import org.dbdatasync.connector.sqlite.cdc.TriggerLogListener;
import org.dbdatasync.sdk.cdc.trigger.AbstractTriggerLogListener;
import org.dbdatasync.sdk.listener.event.RowChangedEvent;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.Table;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Wave26：真实库全链路 E2E Harness（Trigger CDC 捕获 → 目标端真实应用 → 切流 SQL 真实执行）。
 *
 * <p>与 {@code RealDbE2eHarness}（仅前置检查）不同，本类执行真实流水线：</p>
 * <ol>
 *     <li>真实建源/目标库表（文件库，非内存 mock）；</li>
 *     <li>Trigger CDC 幂等装触发器 + 捕获位点；</li>
 *     <li>真实 DML（INSERT/UPDATE/DELETE）→ 轮询还原行事件（校验 op/值/源端时间）；</li>
 *     <li>把事件真实写入目标库并校验两端一致；</li>
 *     <li>切流执行器 {@link DefaultCutoverExecutor}：注册真实动作处理器 + 参数 SQL 在目标库真实执行，
 *         校验台账 executed 与 simulated 语义。</li>
 * </ol>
 *
 * <p>运行（无需连接串时默认用本机 H2/SQLite 文件库，它们也是真实库）：
 * <pre>
 *   java -cp "dist/lib/*" tools/e2e/RealDbPipelineE2e.java [e2e.properties]
 * </pre>
 * properties 可选键：{@code e2e.vendor=h2|sqlite}、{@code source.url}、{@code target.url}
 * （外部连接串提供后即用外部真实库执行同一条流水线；MySQL/PG 等需其 Trigger CDC 方言，
 * 未提供时请在 RealDbE2eHarness 前置检查后按 RUNBOOK 走控制台迁移）。
 * 退出码：0=全链路通过；2=存在校验失败；1=运行异常。</p>
 *
 * @author DBDataSync
 * @version 2.1.6
 * @date 2026-11-09
 */
public class RealDbPipelineE2e {

    private static final String SOURCE_TABLE = "E2E_EMPLOYEE";
    private static final String TARGET_TABLE = "E2E_EMPLOYEE";
    private static final String CUTOVER_LOG_TABLE = "E2E_CUTOVER_LOG";

    private final List<String> failures = new ArrayList<String>();
    private int passed = 0;

    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        if (args.length >= 1) {
            java.io.FileInputStream in = new java.io.FileInputStream(args[0]);
            try {
                p.load(new java.io.InputStreamReader(in, "UTF-8"));
            } finally {
                in.close();
            }
        }
        int code = new RealDbPipelineE2e().run(p);
        System.exit(code);
    }

    int run(Properties p) {
        System.out.println("==== DBDataSync 真实库全链路 E2E ====");
        try {
            String vendor = normalizeVendor(p.getProperty("e2e.vendor", "h2"));
            Path work = Files.createTempDirectory("dbds-real-e2e");
            String sourceUrl = p.getProperty("source.url", defaultUrl(vendor, work, "source"));
            String targetUrl = p.getProperty("target.url", defaultUrl(vendor, work, "target"));
            System.out.println("vendor=" + vendor);
            System.out.println("source=" + sourceUrl);
            System.out.println("target=" + targetUrl);

            createTables(sourceUrl, targetUrl);
            AbstractTriggerLogListener listener = newListener(vendor, sourceUrl);
            listener.installSchema();
            listener.installTriggers();
            listener.captureSnapshot();
            dml(sourceUrl);
            int captured = listener.poll();
            // 清空源表：让第二轮 DML 从干净状态开始；清理产生的 DELETE 事件
            // 序列号会被第二轮 captureSnapshot 位点跳过，不会混入第二轮事件流
            try (Connection c = DriverManager.getConnection(sourceUrl); Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM " + SOURCE_TABLE);
            }
            listener.close();
            check("Trigger CDC 捕获事件数", captured == 4,
                    "captured=" + captured + "（期望 4：2 插入+1 更新+1 删除）");

            List<RowChangedEvent> events = new ArrayList<RowChangedEvent>();
            AbstractTriggerLogListener listener2 = newListener(vendor, sourceUrl);
            listener2.setEventSink(events::add);
            listener2.captureSnapshot();
            listener2.installSchema();
            listener2.installTriggers();
            dml(sourceUrl);
            listener2.poll();
            listener2.close();

            check("事件流完整性", events.size() == 4, "events=" + events.size());
            if (events.size() == 4) {
                check("INSERT 事件", "INSERT".equalsIgnoreCase(events.get(0).getEvent()),
                        String.valueOf(events.get(0).getEvent()));
                check("第二条 INSERT 事件", "INSERT".equalsIgnoreCase(events.get(1).getEvent()),
                        String.valueOf(events.get(1).getEvent()));
                check("UPDATE 事件", "UPDATE".equalsIgnoreCase(events.get(2).getEvent()),
                        String.valueOf(events.get(2).getEvent()));
                check("DELETE 事件", "DELETE".equalsIgnoreCase(events.get(3).getEvent()),
                        String.valueOf(events.get(3).getEvent()));
                check("事件带源端时间", events.get(0).getChangedOffset().isSourceEventTime(),
                        "sourceEventTime=" + events.get(0).getChangedOffset().isSourceEventTime());
                check("更新值还原", String.valueOf(events.get(2).getChangedRow().get(1)).contains("bob2"),
                        "row=" + events.get(2).getChangedRow());
                check("NULL 值还原", events.get(1).getChangedRow().get(1) == null,
                        "row=" + events.get(1).getChangedRow());
                applyToTarget(targetUrl, events, vendor);
                verifyTarget(sourceUrl, targetUrl);
            }

            cutoverFlow(targetUrl);
        } catch (Exception e) {
            failures.add("运行异常: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("==== 结果汇总 ====");
        System.out.println("通过 " + passed + " 项");
        if (failures.isEmpty()) {
            System.out.println("[OK] 真实库全链路 E2E 通过（建表→CDC 捕获→还原→目标应用→一致性→切流 SQL）");
            return 0;
        }
        System.out.println("[FAIL] 失败 " + failures.size() + " 项：");
        for (String f : failures) {
            System.out.println("  - " + f);
        }
        return 2;
    }

    // ---------- 流水线步骤 ----------

    private void createTables(String sourceUrl, String targetUrl) throws Exception {
        String ddl = "CREATE TABLE " + SOURCE_TABLE + " (id INT PRIMARY KEY, name VARCHAR(100), salary INT)";
        try (Connection c = DriverManager.getConnection(sourceUrl); Statement st = c.createStatement()) {
            try { st.executeUpdate("DROP TABLE " + SOURCE_TABLE); } catch (Exception ignore) { }
            st.executeUpdate(ddl);
        }
        try (Connection c = DriverManager.getConnection(targetUrl); Statement st = c.createStatement()) {
            try { st.executeUpdate("DROP TABLE " + TARGET_TABLE); } catch (Exception ignore) { }
            st.executeUpdate(ddl);
            try { st.executeUpdate("DROP TABLE " + CUTOVER_LOG_TABLE); } catch (Exception ignore) { }
            st.executeUpdate("CREATE TABLE " + CUTOVER_LOG_TABLE + " (phase VARCHAR(64), detail VARCHAR(500), ts BIGINT)");
        }
        check("源/目标表创建", true, SOURCE_TABLE + " + " + CUTOVER_LOG_TABLE);
    }

    private AbstractTriggerLogListener newListener(String vendor, String sourceUrl) throws Exception {
        Table table = new Table();
        table.setName(SOURCE_TABLE);
        table.setColumn(Arrays.<Field>asList(
                new Field("id", "INT", Types.INTEGER),
                new Field("name", "VARCHAR", Types.VARCHAR),
                new Field("salary", "INT", Types.INTEGER)));
        AbstractTriggerLogListener listener;
        if ("sqlite".equals(vendor)) {
            listener = new TriggerLogListener();
        } else {
            listener = new H2TriggerLogListener();
        }
        final String url = sourceUrl;
        listener.setConnectionSupplier(new AbstractTriggerLogListener.ConnectionSupplier() {
            public Connection get() throws Exception {
                return DriverManager.getConnection(url);
            }
        });
        listener.setSourceTable(Collections.singletonList(table));
        listener.setFilterTable(new HashSet<String>(Collections.singletonList(SOURCE_TABLE)));
        return listener;
    }

    private void dml(String sourceUrl) throws Exception {
        try (Connection c = DriverManager.getConnection(sourceUrl); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO " + SOURCE_TABLE + " VALUES (1, 'alice', 100)");
            st.executeUpdate("INSERT INTO " + SOURCE_TABLE + " VALUES (2, NULL, 200)");
            st.executeUpdate("UPDATE " + SOURCE_TABLE + " SET name = 'bob2''s', salary = 220 WHERE id = 2");
            st.executeUpdate("DELETE FROM " + SOURCE_TABLE + " WHERE id = 1");
        }
    }

    /** 把捕获事件按 upsert 语义真实写入目标库 */
    private void applyToTarget(String targetUrl, List<RowChangedEvent> events, String vendor) throws Exception {
        boolean sqlite = "sqlite".equals(vendor);
        int applied = 0;
        for (RowChangedEvent event : events) {
            List<Object> row = event.getChangedRow();
            String op = String.valueOf(event.getEvent()).toUpperCase(java.util.Locale.ROOT);
            try (Connection c = DriverManager.getConnection(targetUrl)) {
                if ("DELETE".equals(op)) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "DELETE FROM " + TARGET_TABLE + " WHERE id = ?")) {
                        ps.setObject(1, row.get(0));
                        ps.executeUpdate();
                        applied++;
                    }
                } else if (sqlite) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT OR REPLACE INTO " + TARGET_TABLE + " (id, name, salary) VALUES (?, ?, ?)")) {
                        ps.setObject(1, row.get(0));
                        ps.setObject(2, row.get(1));
                        ps.setObject(3, row.get(2));
                        ps.executeUpdate();
                        applied++;
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement(
                            "MERGE INTO " + TARGET_TABLE + " (id, name, salary) KEY (id) VALUES (?, ?, ?)")) {
                        ps.setObject(1, row.get(0));
                        ps.setObject(2, row.get(1));
                        ps.setObject(3, row.get(2));
                        ps.executeUpdate();
                        applied++;
                    }
                }
            }
        }
        check("目标端真实应用事件", applied == events.size(), "applied=" + applied);
    }

    private void verifyTarget(String sourceUrl, String targetUrl) throws Exception {
        List<String> sourceRows = new ArrayList<String>();
        try (Connection source = DriverManager.getConnection(sourceUrl);
             Statement st = source.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name, salary FROM " + SOURCE_TABLE + " ORDER BY id")) {
            while (rs.next()) {
                sourceRows.add(rs.getInt(1) + "|" + rs.getString(2) + "|" + rs.getInt(3));
            }
        }
        List<String> targetRows = new ArrayList<String>();
        try (Connection target = DriverManager.getConnection(targetUrl);
             Statement ts = target.createStatement();
             ResultSet trs = ts.executeQuery("SELECT id, name, salary FROM " + TARGET_TABLE + " ORDER BY id")) {
            while (trs.next()) {
                targetRows.add(trs.getInt(1) + "|" + trs.getString(2) + "|" + trs.getInt(3));
            }
        }
        check("两端数据一致", sourceRows.equals(targetRows),
                "source=" + sourceRows + " target=" + targetRows);
    }

    // ---------- 切流流 ----------

    private void cutoverFlow(String targetUrl) throws Exception {
        DefaultCutoverExecutor executor = new DefaultCutoverExecutor();
        final String tUrl = targetUrl;
        executor.register(new CutoverExecutor.ActionHandler() {
            public String handle(String action, String taskId, Map<String, String> params) throws Exception {
                // 真实动作：把切流相位写入目标库切流台账表（真实 SQL，非模拟）
                try (Connection c = DriverManager.getConnection(tUrl);
                     PreparedStatement ps = c.prepareStatement(
                             "INSERT INTO " + CUTOVER_LOG_TABLE + "(phase, detail, ts) VALUES (?,?,?)")) {
                    ps.setString(1, action);
                    ps.setString(2, "handler-executed task=" + taskId);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return "切流台账已写入目标库（action=" + action + "）";
            }
        });
        executor.setConnectionProvider(new org.dbdatasync.biz.dblink.ConnectionProvider() {
            public Connection get() throws java.sql.SQLException {
                return DriverManager.getConnection(tUrl);
            }
        });
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("sql", "SELECT COUNT(*) FROM " + TARGET_TABLE);
        CutoverExecutor.ActionResult result = executor.execute("e2e-task-1", CutoverPhase.SWITCHING, params);

        int executedSteps = 0;
        for (Map<String, Object> step : result.getSteps()) {
            System.out.println("  [cutover] " + step.get("action") + " executed=" + step.get("executed")
                    + " detail=" + step.get("detail"));
            if (Boolean.TRUE.equals(step.get("executed"))) {
                executedSteps++;
            }
        }
        check("切流动作真实执行（含目标端 COUNT SQL）", executedSteps >= 3 && !result.isSimulated(),
                "ok=" + result.isOk() + " simulated=" + result.isSimulated()
                        + " executedSteps=" + executedSteps + " msg=" + result.getMessage());
        check("切流台账落库", countRows(targetUrl, CUTOVER_LOG_TABLE) >= 2,
                "cutoverLogRows=" + countRows(targetUrl, CUTOVER_LOG_TABLE));
    }

    private int countRows(String url, String table) throws Exception {
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ---------- 工具 ----------

    private String normalizeVendor(String vendor) {
        String v = vendor == null ? "h2" : vendor.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"h2".equals(v) && !"sqlite".equals(v)) {
            System.out.println("[WARN] vendor=" + v + " 不在本 harness 流水线支持内（h2/sqlite），回退 h2");
            v = "h2";
        }
        return v;
    }

    private String defaultUrl(String vendor, Path work, String role) {
        if ("sqlite".equals(vendor)) {
            return "jdbc:sqlite:" + work.resolve(role + ".db").toAbsolutePath();
        }
        return "jdbc:h2:" + work.resolve(role).toAbsolutePath();
    }

    private void check(String item, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + item + "：" + detail);
        } else {
            failures.add(item + "：" + detail);
            System.out.println("[FAIL] " + item + "：" + detail);
        }
    }
}
