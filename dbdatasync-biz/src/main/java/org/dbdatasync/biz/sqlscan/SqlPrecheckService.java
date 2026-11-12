package org.dbdatasync.biz.sqlscan;

import org.dbdatasync.biz.dblink.ConnectionProvider;
import org.dbdatasync.biz.vo.SqlPrecheckReportVO;
import org.dbdatasync.common.util.StringUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 应用 SQL 兼容度预检服务（Wave10 P1-3，独立链路）。
 *
 * <p>两类输入：<strong>应用 SQL 文本（多语句）</strong> 与 <strong>本地 SQL 目录（纯文件 IO）</strong>；
 * 合并后复用既有 {@link SqlCompatibilityScanner#scan(String, String, String)}，
 * 报告口径对齐 {@link SqlScanResult}。</p>
 *
 * <p>默认仅提示、不阻断：{@code blocking=false} 固定，不新增阻断开关、不写入任务、
 * 不改动 {@code DatabaseSyncController.add()/edit()} 与 {@code POST /precheck} /
 * {@code PreCheckService.assertPassable}。未输入时<strong>零副作用（不调用扫描器）</strong>。</p>
 *
 * <p>目录安全边界（常量集中在本类）：深度 ≤ {@value #MAX_DEPTH}、文件数 ≤ {@value #MAX_FILES}、
 * 单文件 ≤ 1MB、总计 ≤ 10MB；拒绝文件系统根、家目录、{@code /etc}、{@code /Windows}、
 * {@code C:/} 等系统路径；仅 {@code *.sql}（大小写不敏感）、UTF-8 读取、按文件名排序拼接。
 * 不遍历系统目录、不连库、不执行任何 SQL、不自动执行 DDL。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class SqlPrecheckService {

    /** 目录遍历最大深度 */
    public static final int MAX_DEPTH = 3;

    /** 最大 .sql 文件数 */
    public static final int MAX_FILES = 200;

    /** 单文件最大字节（1MB） */
    public static final long MAX_FILE_BYTES = 1L * 1024 * 1024;

    /** 总字节上限（10MB） */
    public static final long MAX_TOTAL_BYTES = 10L * 1024 * 1024;

    /** 固定提示语 */
    public static final String HINT = "仅提示，不阻断任务创建";

    /** Wave24：真实预检单批最多校验的语句数 */
    public static final int MAX_RUNTIME_STATEMENTS = 200;

    private SqlCompatibilityScanner scanner;

    /** Wave24：可选的目标库连接提供者；为 null 时真实预检跳过，静态扫描不受影响 */
    private ConnectionProvider connectionProvider;

    public SqlPrecheckService() {
        this.scanner = new SqlCompatibilityScanner();
    }

    /** 供单测注入桩/计数扫描器（不改变默认无参构造行为） */
    public void setScanner(SqlCompatibilityScanner scanner) {
        this.scanner = scanner == null ? new SqlCompatibilityScanner() : scanner;
    }

    /** Wave24：注入目标库连接（可由 DBLink 连接器适配），未注入时不会连库 */
    public void setConnectionProvider(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /**
     * 生成兼容度预检报告。
     *
     * @param appSqlText 应用 SQL 文本（多语句，可选）
     * @param appSqlDir  本地 SQL 目录（纯文件 IO，可选）
     * @param src        源方言
     * @param tgt        目标方言
     * @return 预检报告（{@code blocking=false}；未输入时 {@code result=null} 且零副作用）
     */
    public SqlPrecheckReportVO precheck(String appSqlText, String appSqlDir, String src, String tgt) {
        return precheck(appSqlText, appSqlDir, src, tgt, false, false);
    }

    /**
     * Wave24 重载：{@code runtimeCheck=true} 时用可选连接做真实预检
     * （默认 prepareStatement 只做语法/对象解析、不执行；{@code explain=true} 时对
     * SELECT 执行只读 EXPLAIN）。不注入连接时跳过，报告仍为静态扫描结果。
     * {@code blocking} 依旧固定 false，不改变任务创建流程。
     */
    public SqlPrecheckReportVO precheck(String appSqlText, String appSqlDir, String src, String tgt,
                                        boolean runtimeCheck, boolean explain) {
        SqlPrecheckReportVO report = new SqlPrecheckReportVO();
        report.setHint(HINT);
        report.setBlocking(false);

        String text = appSqlText == null ? "" : appSqlText.trim();
        String dirInput = appSqlDir == null ? "" : appSqlDir.trim();
        List<String> notices = new ArrayList<>();
        String dirSql = "";
        int fileCount = 0;
        long totalBytes = 0L;

        if (StringUtil.isNotBlank(dirInput)) {
            DirectoryContent content = readSqlFromDirectory(dirInput);
            dirSql = content.sql;
            fileCount = content.fileCount;
            totalBytes = content.totalBytes;
            notices.addAll(content.warnings);
        }

        String merged = text;
        if (!dirSql.trim().isEmpty()) {
            merged = merged.isEmpty() ? dirSql : merged + "\n;\n" + dirSql;
        }

        String mode;
        if (!text.isEmpty() && !dirSql.trim().isEmpty()) {
            mode = "mixed";
        } else if (!text.isEmpty()) {
            mode = "text";
        } else if (!dirSql.trim().isEmpty()) {
            mode = "directory";
        } else {
            mode = "none";
        }

        StringBuilder summary = new StringBuilder();
        if (!text.isEmpty()) {
            summary.append("SQL 文本 ").append(text.length()).append(" 字符");
        }
        if (!dirSql.trim().isEmpty()) {
            if (summary.length() > 0) {
                summary.append("；");
            }
            summary.append("目录 ").append(fileCount).append(" 个 .sql 文件 / ")
                    .append(totalBytes).append(" 字节");
        }
        if (summary.length() == 0) {
            // 完全未输入：保留既有「未输入」文案；填了目录但无可执行 SQL 时用更准确的措辞
            if (notices.isEmpty()) {
                summary.append("未输入，未执行扫描");
            } else {
                summary.append("未获取到可执行扫描的 SQL 内容，未执行扫描");
            }
        }
        for (String notice : notices) {
            if (StringUtil.isNotBlank(notice)) {
                summary.append("；").append(notice);
            }
        }
        report.setInputSummary(summary.toString());

        if (merged.trim().isEmpty()) {
            // 未输入（或目录无有效 .sql）：零副作用，不调用扫描器
            report.setInputMode("none");
            report.setResult(null);
            return report;
        }
        report.setInputMode(mode);
        report.setResult(scanner.scan(merged, src, tgt));
        if (runtimeCheck) {
            report.setRuntimeCheckEnabled(true);
            List<Map<String, Object>> checks = runRuntimeChecks(merged, explain);
            report.setRuntimeChecks(checks);
            int passed = 0;
            for (Map<String, Object> check : checks) {
                if (Boolean.TRUE.equals(check.get("ok"))) {
                    passed++;
                }
            }
            report.setRuntimeCheckSummary("真实预检 " + checks.size() + " 条语句，通过 " + passed
                    + " 条，失败 " + (checks.size() - passed) + " 条；模式="
                    + (explain ? "EXPLAIN(SELECT)/prepare(其它)" : "prepare(仅解析不执行)"));
        }
        return report;
    }

    /** Wave24：逐语句真实预检。绝不执行 DML/DDL；EXPLAIN 仅对只读 SELECT 执行。 */
    private List<Map<String, Object>> runRuntimeChecks(String sql, boolean explain) {
        List<Map<String, Object>> checks = new ArrayList<>();
        if (connectionProvider == null) {
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("ok", false);
            skipped.put("mode", "skipped");
            skipped.put("message", "未配置目标库连接器，真实预检跳过；请传 connectorId");
            checks.add(skipped);
            return checks;
        }
        java.sql.Connection connection = null;
        try {
            connection = connectionProvider.get();
        } catch (Exception e) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("mode", "connect");
            failed.put("message", String.valueOf(e.getMessage()));
            checks.add(failed);
            return checks;
        }
        try {
            for (String raw : new SqlCompatibilityScanner().splitStatements(sql)) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String statement = raw.trim();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("statement", statement.length() > 200 ? statement.substring(0, 200) + "..." : statement);
                boolean select = isReadOnlySelect(statement);
                String toCheck = explain && select ? "EXPLAIN " + statement : statement;
                item.put("mode", explain && select ? "explain" : "prepare");
                try (java.sql.PreparedStatement ps = connection.prepareStatement(toCheck)) {
                    if (explain && select) {
                        // 只读执行 EXPLAIN，不触碰业务数据
                        ps.execute();
                    }
                    item.put("ok", true);
                    item.put("message", explain && select ? "EXPLAIN 执行成功" : "prepare 解析成功（未执行 SQL）");
                } catch (java.sql.SQLException e) {
                    item.put("ok", false);
                    item.put("errorCode", e.getErrorCode());
                    item.put("sqlState", e.getSQLState());
                    item.put("message", String.valueOf(e.getMessage()));
                }
                checks.add(item);
                if (checks.size() >= MAX_RUNTIME_STATEMENTS) {
                    break;
                }
            }
        } catch (Exception e) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("mode", "split");
            failed.put("message", String.valueOf(e.getMessage()));
            checks.add(failed);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (java.sql.SQLException ignored) {
                    // 池化连接归还失败不影响预检结论
                }
            }
        }
        return checks;
    }

    private static boolean isReadOnlySelect(String statement) {
        String upper = statement.toUpperCase(Locale.ROOT);
        return upper.startsWith("SELECT") || upper.startsWith("WITH");
    }

    /**
     * 读取目录（纯文件 IO + 安全边界），返回按文件名排序拼接的 SQL 文本。
     *
     * @param dir 已 normalize 的目录
     * @return 拼接后的 SQL；硬越界/不存在时抛 {@link IllegalArgumentException}（由调用方转可读提示）
     */
    private String readDirectory(Path dir, DirectoryContent out) {
        assertSafeDirectory(dir);
        List<Path> files = listSqlFiles(dir);
        StringBuilder sql = new StringBuilder();
        long total = 0L;
        int count = 0;
        int skippedLargeFiles = 0;
        for (Path file : files) {
            if (count >= MAX_FILES) {
                break;
            }
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                continue;
            }
            if (size > MAX_FILE_BYTES || total + size > MAX_TOTAL_BYTES) {
                // 单文件超 1MB 单独计数（总量截断已有 readSqlFromDirectory 的提示，不重复报）
                if (size > MAX_FILE_BYTES) {
                    skippedLargeFiles++;
                }
                // 软越界：跳过该文件，不中断其它输入
                continue;
            }
            String content;
            try {
                content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            if (!content.trim().isEmpty()) {
                if (sql.length() > 0) {
                    sql.append("\n;\n");
                }
                sql.append(content);
            }
            total += size;
            count++;
        }
        if (skippedLargeFiles > 0) {
            out.warnings.add("目录中有 " + skippedLargeFiles
                    + " 个 .sql 文件超过单文件 1MB 上限，已跳过");
        }
        return sql.toString();
    }

    /** 目录输入包装：安全校验 + 统计 + 可读提示（不中断其它输入） */
    private DirectoryContent readSqlFromDirectory(String dirInput) {
        DirectoryContent content = new DirectoryContent();
        Path dir;
        try {
            dir = Paths.get(dirInput).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            content.warnings.add("目录路径无效：" + dirInput);
            return content;
        }
        try {
            assertSafeDirectory(dir);
            if (!Files.exists(dir) || !Files.isDirectory(dir) || !Files.isReadable(dir)) {
                content.warnings.add("目录不存在或不可读：" + dir);
                return content;
            }
            List<Path> files = listSqlFiles(dir);
            if (files.isEmpty()) {
                content.warnings.add("目录内未找到 .sql 文件：" + dir);
                return content;
            }
            content.fileCount = files.size();
            long bytes = 0L;
            for (Path file : files) {
                try {
                    bytes += Files.size(file);
                } catch (IOException ignore) {
                    // 单个文件不可读不影响统计
                }
            }
            content.totalBytes = bytes;
            if (content.fileCount > MAX_FILES || bytes > MAX_TOTAL_BYTES) {
                content.warnings.add("目录 .sql 文件数量或总字节超过上限（≤" + MAX_FILES
                        + " 个文件 / ≤" + MAX_TOTAL_BYTES + " 字节），已按上限截断。");
            }
            content.sql = readDirectory(dir, content);
        } catch (IllegalArgumentException e) {
            content.warnings.add(e.getMessage());
        } catch (RuntimeException e) {
            content.warnings.add("目录读取失败：" + dir);
        }
        return content;
    }

    /** 仅 *.sql 常规文件、限定深度、按文件名排序 */
    private List<Path> listSqlFiles(Path dir) {
        try (Stream<Path> stream = Files.walk(dir, MAX_DEPTH)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter((Path p) -> p.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .sorted(Comparator.comparing(
                            (Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalArgumentException("目录读取失败：" + dir);
        }
    }

    /**
     * 安全边界：拒绝文件系统根目录、用户家目录、/etc、/Windows、C:/ 等系统路径。
     * 仅拒绝这些路径<strong>本身或其系统子树</strong>，不影响临时目录等普通子目录。
     */
    private void assertSafeDirectory(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        Path root = abs.getRoot();
        if (root != null && abs.equals(root)) {
            throw new IllegalArgumentException("不允许扫描文件系统根目录：" + abs);
        }
        String home = System.getProperty("user.home", "");
        boolean isHomeDirectory = false;
        if (StringUtil.isNotBlank(home)) {
            try {
                Path homePath = Paths.get(home).toAbsolutePath().normalize();
                isHomeDirectory = abs.equals(homePath);
            } catch (RuntimeException ignore) {
                // 家目录解析失败时不阻断其它输入
                isHomeDirectory = false;
            }
        }
        if (isHomeDirectory) {
            // 必须在 try 外抛出：IllegalArgumentException 属于 RuntimeException，
            // 放在 try 内会被「解析失败」的 catch 吞掉，导致家目录被实际遍历。
            throw new IllegalArgumentException("不允许扫描用户家目录：" + abs);
        }
        if (root != null) {
            Path relative = root.relativize(abs);
            if (relative.getNameCount() > 0) {
                String first = relative.getName(0).toString().toLowerCase(Locale.ROOT);
                if ("etc".equals(first) || "windows".equals(first) || "winnt".equals(first)
                        || "program files".equals(first) || "program files (x86)".equals(first)) {
                    throw new IllegalArgumentException("不允许扫描系统目录：" + abs);
                }
            }
        }
        String forward = abs.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (forward.startsWith("/etc") || forward.startsWith("/windows")) {
            throw new IllegalArgumentException("不允许扫描系统目录：" + abs);
        }
    }

    /** 目录读取结果（内部载体） */
    private static final class DirectoryContent {
        private String sql = "";
        private int fileCount = 0;
        private long totalBytes = 0L;
        private final List<String> warnings = new ArrayList<>();
    }
}
