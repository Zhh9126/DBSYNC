package org.dbdatasync.common.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行态统一账本。
 *
 * <p>平台元库是内嵌 H2 文件库（jdbc:h2:file:./data/DBDataSync），原先只有配置/任务/结果
 * 等 9 张表走 H2；慢 SQL、超大内容外置块、结构变更账本写 data/runtime 下的文件，
 * 切流编排与链路健康计数器只存在进程内存里，服务重启即丢失。本类把这些运行态数据
 * 统一落到同一 H2 文件的 runtime_ledger 表，保证「所有数据都在数据库里、重启不丢」。
 *
 * <p>设计约束：本类位于 common 模块，不能依赖 Spring / biz / parser；由 web 启动时
 * 通过 {@link #init(String)} 注入与平台一致的 H2 URL。未初始化或元库不可用时，
 * 所有写入静默降级为 no-op，各业务模块原有的文件/内存路径继续生效，绝不影响主链路。
 */
public final class RuntimeLedger {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeLedger.class);
    private static final String TABLE = "DBDataSync_runtime_ledger";

    public static final String KIND_SLOW_SQL = "slow_sql";
    public static final String KIND_CONTENT_CHUNK = "content_chunk";
    public static final String KIND_SCHEMA_HISTORY = "schema_history";
    public static final String KIND_CUTOVER = "cutover";
    public static final String KIND_LINK_HEALTH = "link_health";

    private static Connection holder;
    private static boolean initialized;

    private RuntimeLedger() {
    }

    /** 由 Spring 配置在应用启动时调用，url 必须与 dbdatasync.storage.h2.url 完全一致。 */
    public static synchronized void init(String url, String user, String password) {
        if (initialized) {
            return;
        }
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            boolean sqlite = url.trim().startsWith("jdbc:sqlite:");
            Class.forName(sqlite ? "org.sqlite.JDBC" : "org.h2.Driver");
            String jdbcUrl = url.trim();
            if (sqlite && !jdbcUrl.contains("?")) {
                // 与元库共用同一 SQLite 文件时，独立连接同样需要 busy_timeout，
                // 否则与平台写线程池并发时会抛 SQLITE_BUSY。
                jdbcUrl = jdbcUrl + "?busy_timeout=15000&journal_mode=WAL&synchronous=NORMAL";
            }
            holder = DriverManager.getConnection(jdbcUrl,
                    sqlite ? "" : (user == null ? "sa" : user),
                    sqlite ? "" : (password == null ? "" : password));
            try (Statement st = holder.createStatement()) {
                // SQLite 要求自增主键写成 INTEGER PRIMARY KEY AUTOINCREMENT，
                // 且无 CLOB 类型；H2 保持原 DDL 不变。
                String ddl = sqlite
                        ? "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "KIND TEXT, REF_ID TEXT, "
                        + "PAYLOAD TEXT, CREATE_TIME BIGINT)"
                        : "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "ID BIGINT AUTO_INCREMENT PRIMARY KEY, "
                        + "KIND VARCHAR(64), REF_ID VARCHAR(128), "
                        + "PAYLOAD CLOB, CREATE_TIME BIGINT)";
                st.execute(ddl);
                st.execute("CREATE INDEX IF NOT EXISTS IDX_RUNTIME_LEDGER_KIND ON "
                        + TABLE + "(KIND, REF_ID)");
            }
            initialized = true;
            LOG.info("[RuntimeLedger] 运行态统一账本已就绪, table={}, sqlite={}", TABLE, sqlite);
        } catch (Exception e) {
            holder = null;
            LOG.warn("[RuntimeLedger] 账本初始化失败, 运行态数据仍按原有文件/内存方式保存: {}", e.toString());
        }
    }

    public static boolean isReady() {
        return initialized;
    }

    /** 追加一条运行态记录；返回自增 ID，未就绪或失败返回 -1。 */
    public static synchronized long append(String kind, String refId, String payload) {
        if (!initialized || kind == null) {
            return -1;
        }
        String sql = "INSERT INTO " + TABLE + " (KIND, REF_ID, PAYLOAD, CREATE_TIME) VALUES (?,?,?,?)";
        try (PreparedStatement ps = holder.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, kind);
            ps.setString(2, refId == null ? "" : refId);
            ps.setString(3, payload == null ? "" : payload);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            LOG.warn("[RuntimeLedger] append 失败 kind={} refId={}: {}", kind, refId, e.toString());
        }
        return -1;
    }

    public static synchronized List<Entry> list(String kind, String refId, int limit) {
        List<Entry> out = new ArrayList<>();
        if (!initialized) {
            return out;
        }
        StringBuilder sql = new StringBuilder("SELECT ID, KIND, REF_ID, PAYLOAD, CREATE_TIME FROM ")
                .append(TABLE).append(" WHERE 1=1");
        if (kind != null && !kind.isEmpty()) {
            sql.append(" AND KIND=?");
        }
        if (refId != null && !refId.isEmpty()) {
            sql.append(" AND REF_ID=?");
        }
        sql.append(" ORDER BY ID DESC");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }
        try (PreparedStatement ps = holder.prepareStatement(sql.toString())) {
            int i = 1;
            if (kind != null && !kind.isEmpty()) {
                ps.setString(i++, kind);
            }
            if (refId != null && !refId.isEmpty()) {
                ps.setString(i, refId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5)));
                }
            }
        } catch (Exception e) {
            LOG.warn("[RuntimeLedger] list 失败: {}", e.toString());
        }
        return out;
    }

    public static synchronized long count(String kind) {
        if (!initialized) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM " + TABLE + (kind == null ? "" : " WHERE KIND=?");
        try (PreparedStatement ps = holder.prepareStatement(sql)) {
            if (kind != null) {
                ps.setString(1, kind);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            LOG.warn("[RuntimeLedger] count 失败: {}", e.toString());
        }
        return 0;
    }

    /** 清空指定类别（对应页面「清空当前查询数据」时保留审计轨迹用）。 */
    public static synchronized int delete(String kind, String refId) {
        if (!initialized) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(TABLE).append(" WHERE KIND=?");
        if (refId != null && !refId.isEmpty()) {
            sql.append(" AND REF_ID=?");
        }
        try (PreparedStatement ps = holder.prepareStatement(sql.toString())) {
            ps.setString(1, kind);
            if (refId != null && !refId.isEmpty()) {
                ps.setString(2, refId);
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[RuntimeLedger] delete 失败: {}", e.toString());
            return 0;
        }
    }

    public static final class Entry {
        private final long id;
        private final String kind;
        private final String refId;
        private final String payload;
        private final long createTime;

        Entry(long id, String kind, String refId, String payload, long createTime) {
            this.id = id;
            this.kind = kind;
            this.refId = refId;
            this.payload = payload;
            this.createTime = createTime;
        }

        public long getId() { return id; }
        public String getKind() { return kind; }
        public String getRefId() { return refId; }
        public String getPayload() { return payload; }
        public long getCreateTime() { return createTime; }
    }
}
