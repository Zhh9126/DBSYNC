import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * DBDataSync 真机 E2E 前置检查 Harness（CDC 四路径：Oracle→达梦/金仓、MySQL→达梦/金仓）。
 *
 * <p><b>定位</b>：本类不执行迁移本体（迁移走产品 18686 控制台），它做的是迁移前必须满足的
 * <b>源端 CDC 前置条件检查 + 目标端连通性检查 + JDBC 类型往返冒烟</b>，
 * 即《MIGRATION_REALTIME_SYNC_CAPABILITY_VERIFICATION.md》§4 列出的真机前置项。
 *
 * <p><b>运行方式（需在有真实库与对应 JDBC 驱动的环境执行）</b>：
 * <pre>
 *   java -cp "dbdatasync-web/target/lib/*" tools/e2e/RealDbE2eHarness.java e2e.properties
 * </pre>
 * properties 键（未配置的源端自动跳过）：
 * <pre>
 *   oracle.url=jdbc:oracle:thin:@//host:1521/ORCLPDB1
 *   oracle.user=...   oracle.password=...
 *   mysql.url=jdbc:mysql://host:3306/db?useSSL=false
 *   mysql.user=...    mysql.password=...
 *   dm.url=jdbc:dm://host:5236        dm.user=...    dm.password=...
 *   kingbase.url=jdbc:kingbase8://host:54321/db  kingbase.user=... kingbase.password=...
 * </pre>
 * 退出码：0=全部配置项通过；2=存在 FAIL（不可迁移）；1=运行异常。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class RealDbE2eHarness {

    private static final String PASS = "PASS";
    private static final String WARN = "WARN";
    private static final String FAIL = "FAIL";
    private static final String SKIP = "SKIP";

    private final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: java -cp \"<lib/*>\" tools/e2e/RealDbE2eHarness.java e2e.properties");
            System.exit(1);
        }
        Properties p = new Properties();
        java.io.FileInputStream in = new java.io.FileInputStream(args[0]);
        try {
            p.load(new java.io.InputStreamReader(in, "UTF-8"));
        } finally {
            in.close();
        }
        int code = new RealDbE2eHarness().run(p);
        System.exit(code);
    }

    int run(Properties p) {
        System.out.println("==== DBDataSync 真机 E2E 前置检查 ====");
        checkOracle(p);
        checkMySql(p);
        checkKingbase(p);
        checkDameng(p);
        System.out.println("==== 结果汇总 ====");
        if (failures.isEmpty()) {
            System.out.println("[OK] 全部配置项通过或跳过。可按 reports/E2E_REAL_DB_RUNBOOK.md 执行产品侧迁移 E2E。");
            return 0;
        }
        System.out.println("[FAIL] 存在 " + failures.size() + " 个阻塞项：");
        for (String f : failures) {
            System.out.println("  - " + f);
        }
        return 2;
    }

    // ============ 源端前置检查 ============

    private void checkOracle(Properties p) {
        String url = p.getProperty("oracle.url");
        if (url == null || url.trim().isEmpty()) {
            line(SKIP, "Oracle", "未配置，跳过");
            return;
        }
        Connection c = connect("Oracle", url, p.getProperty("oracle.user"), p.getProperty("oracle.password"));
        if (c == null) {
            return;
        }
        try {
            // 归档模式（LogMiner 硬前置）
            String logMode = queryOne(c, "SELECT log_mode FROM v$database");
            check("Oracle", "归档模式", "ARCHIVELOG".equalsIgnoreCase(trim(logMode)),
                    "log_mode=" + logMode + "（LogMiner 要求 ARCHIVELOG）");
            // 补充日志
            String supp = queryOne(c, "SELECT supplemental_log_data_min FROM v$database");
            check("Oracle", "最小补充日志", "YES".equalsIgnoreCase(trim(supp)),
                    "supplemental_log_data_min=" + supp
                            + "（需 ALTER DATABASE ADD SUPPLEMENTAL LOG DATA）");
            String force = queryOne(c, "SELECT force_logging FROM v$database");
            check("Oracle", "强制日志", "YES".equalsIgnoreCase(trim(force)),
                    "force_logging=" + force + "（建议 ALTER DATABASE FORCE LOGGING）");
        } finally {
            close(c);
        }
    }

    private void checkMySql(Properties p) {
        String url = p.getProperty("mysql.url");
        if (url == null || url.trim().isEmpty()) {
            line(SKIP, "MySQL", "未配置，跳过");
            return;
        }
        Connection c = connect("MySQL", url, p.getProperty("mysql.user"), p.getProperty("mysql.password"));
        if (c == null) {
            return;
        }
        try {
            String logBin = queryOne(c, "SELECT @@log_bin");
            check("MySQL", "binlog 开启", "1".equals(trim(logBin)), "@@log_bin=" + logBin);
            String fmt = queryOne(c, "SELECT @@binlog_format");
            check("MySQL", "binlog 格式", "ROW".equalsIgnoreCase(trim(fmt)),
                    "@@binlog_format=" + fmt + "（需 SET GLOBAL binlog_format=ROW）");
            String image = queryOne(c, "SELECT @@binlog_row_image");
            check("MySQL", "行镜像", "FULL".equalsIgnoreCase(trim(image)),
                    "@@binlog_row_image=" + image + "（需 FULL，否则更新/删除事件缺列）");
            String sid = queryOne(c, "SELECT @@server_id");
            line(PASS, "MySQL", "server_id=" + sid);
        } finally {
            close(c);
        }
    }

    private void checkKingbase(Properties p) {
        String url = p.getProperty("kingbase.url");
        if (url == null || url.trim().isEmpty()) {
            line(SKIP, "Kingbase", "未配置，跳过");
            return;
        }
        Connection c = connect("Kingbase", url, p.getProperty("kingbase.user"), p.getProperty("kingbase.password"));
        if (c == null) {
            return;
        }
        try {
            String wal = queryOne(c, "SHOW wal_level");
            check("Kingbase", "逻辑解码 wal_level", "logical".equalsIgnoreCase(trim(wal)),
                    "wal_level=" + wal + "（需 wal_level=logical 并重启）");
            String repl = queryOne(c, "SELECT rolreplication FROM pg_roles WHERE rolname = current_user");
            check("Kingbase", "复制角色", "t".equalsIgnoreCase(trim(repl)) || "true".equalsIgnoreCase(trim(repl)),
                    "rolreplication=" + repl + "（需 ALTER USER ... REPLICATION）");
            // 二进制类型映射修复（9f8df12f6）的落库验证：BYTEA 读写往返
            smokeBinaryRoundTrip(c, "Kingbase");
        } finally {
            close(c);
        }
    }

    private void checkDameng(Properties p) {
        String url = p.getProperty("dm.url");
        if (url == null || url.trim().isEmpty()) {
            line(SKIP, "DM", "未配置，跳过");
            return;
        }
        Connection c = connect("DM", url, p.getProperty("dm.user"), p.getProperty("dm.password"));
        if (c == null) {
            return;
        }
        try {
            String ver = queryOne(c, "SELECT BANNER FROM v$version WHERE ROWNUM = 1");
            line(PASS, "DM", "版本=" + ver);
            // 归档模式（DM 兼容 Oracle LogMiner 协议时同样依赖归档）
            try {
                String arch = queryOne(c, "SELECT arch_mode FROM v$database");
                check("DM", "归档模式", "Y".equalsIgnoreCase(trim(arch)) || "1".equals(trim(arch)),
                        "arch_mode=" + arch + "（需开启归档）");
            } catch (Exception e) {
                line(WARN, "DM", "arch_mode 查询失败（版本差异），请人工核对：" + e.getMessage());
            }
        } finally {
            close(c);
        }
    }

    // ============ 目标端连通 + 类型冒烟 ============

    /** Kingbase BYTEA 二进制往返（验证 Wave 修复 9f8df12f6 的落库行为） */
    private void smokeBinaryRoundTrip(Connection c, String vendor) {
        Statement st = null;
        try {
            st = c.createStatement();
            try { st.execute("DROP TABLE DBDS_E2E_SMOKE"); } catch (Exception ignore) { }
            st.execute("CREATE TABLE DBDS_E2E_SMOKE (id INT PRIMARY KEY, payload BYTEA)");
            st.execute("INSERT INTO DBDS_E2E_SMOKE VALUES (1, decode('41424344','hex'))");
            String back = queryOne(c, "SELECT encode(payload, 'hex') FROM DBDS_E2E_SMOKE WHERE id = 1");
            check(vendor, "BYTEA 二进制往返", "41424344".equalsIgnoreCase(trim(back)),
                    "roundtrip=" + back);
            st.execute("DROP TABLE DBDS_E2E_SMOKE");
        } catch (Exception e) {
            line(WARN, vendor, "BYTEA 冒烟未完成（权限/版本差异），请人工执行：" + e.getMessage());
        } finally {
            try { if (st != null) st.close(); } catch (Exception ignore) { }
        }
    }

    // ============ 工具 ============

    private Connection connect(String vendor, String url, String user, String password) {
        try {
            Connection c = DriverManager.getConnection(url, user, password);
            line(PASS, vendor, "连接成功 " + url);
            return c;
        } catch (Exception e) {
            failures.add(vendor + " 连接失败: " + e.getMessage());
            line(FAIL, vendor, "连接失败：" + e.getMessage());
            return null;
        }
    }

    /** 查询单值；查询失败（无权限/版本差异等）返回 null，由调用方按检查项语义判定 */
    private String queryOne(Connection c, String sql) {
        Statement st = null;
        ResultSet rs = null;
        try {
            st = c.createStatement();
            rs = st.executeQuery(sql);
            if (rs.next()) {
                Object v = rs.getObject(1);
                return v == null ? null : String.valueOf(v);
            }
            return null;
        } catch (Exception e) {
            return QUERY_ERROR_PREFIX + e.getMessage();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) { }
            try { if (st != null) st.close(); } catch (Exception ignore) { }
        }
    }

    private static final String QUERY_ERROR_PREFIX = "QUERY_ERROR:";

    private void check(String vendor, String item, boolean ok, String detail) {
        if (ok) {
            line(PASS, vendor, item + "：" + detail);
        } else {
            failures.add(vendor + " " + item + "：" + detail);
            line(FAIL, vendor, item + "：" + detail);
        }
    }

    private void line(String level, String vendor, String msg) {
        System.out.println("[" + level + "] " + vendor + " - " + msg);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private void close(Connection c) {
        try { c.close(); } catch (Exception ignore) { }
    }
}
