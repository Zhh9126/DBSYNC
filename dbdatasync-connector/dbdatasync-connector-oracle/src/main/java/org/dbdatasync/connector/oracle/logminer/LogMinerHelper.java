/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.connector.oracle.logminer;

import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.oracle.OracleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @Author AE86
 * @Version 1.0.0
 * @Date 2023-12-09 20:23
 */
public class LogMinerHelper {

    private static final Logger logger = LoggerFactory.getLogger(LogMinerHelper.class);
    public static final int LOG_MINER_OC_INSERT = 1;
    public static final int LOG_MINER_OC_DELETE = 2;
    public static final int LOG_MINER_OC_UPDATE = 3;
    public static final int LOG_MINER_OC_DDL = 5;
    public static final int LOG_MINER_OC_COMMIT = 7;
    public static final int LOG_MINER_OC_MISSING_SCN = 34;
    public static final int LOG_MINER_OC_ROLLBACK = 36;
    private static final String LOG_MINER_SQL_QUERY_ROLES = "SELECT * FROM USER_ROLE_PRIVS";
    private static final String LOG_MINER_SQL_QUERY_PRIVILEGES = "SELECT * FROM SESSION_PRIVS";
    private static final List<String> LOG_MINER_PRIVILEGES_NEEDED = Arrays.asList("CREATE SESSION", "SELECT ANY TRANSACTION", "SELECT ANY DICTIONARY", "LOGMINING");
    private static final List<String> LOG_MINER_ORACLE_11_PRIVILEGES_NEEDED = Arrays.asList("CREATE SESSION", "SELECT ANY TRANSACTION", "SELECT ANY DICTIONARY");
    private static final String LOG_MINER_SELECT_CATALOG_ROLE_ROLE = "SELECT_CATALOG_ROLE";
    private static final String LOG_MINER_SQL_GET_CURRENT_SCN = "select CURRENT_SCN from V$DATABASE";
    // 兼容达梦等 LogMiner 协议库：V$DATABASE 无 CURRENT_SCN 列时回退 DBMS_FLASHBACK
    private static final String LOG_MINER_SQL_GET_SCN_FALLBACK = "SELECT DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER FROM DUAL";
    private static final String LOG_MINER_SQL_QUERY_HAS_LOGMINER_PACKAGE = "SELECT COUNT(*) AS N FROM DBA_OBJECTS WHERE OBJECT_NAME = 'DBMS_LOGMNR'";
    private static final String LOG_MINER_SQL_IS_CDB = "select cdb from v$database";
    private static final String LOG_MINER_SQL_ALTER_SESSION_CONTAINER = "alter session set container=CDB$ROOT";
    private static final String LOG_MINER_SQL_ALTER_NLS_SESSION_PARAMETERS = "ALTER SESSION SET " + "  NLS_DATE_FORMAT = 'YYYY-MM-DD HH24:MI:SS'"
            + "  NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF'" + "  NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF TZH:TZM'" + "  NLS_NUMERIC_CHARACTERS = '.,'" + "  TIME_ZONE = '00:00'";
    private static final String LOG_MINER_SQL_CURRENT_REDO_SEQUENCE = "SELECT SEQUENCE# FROM V$LOG WHERE STATUS = 'CURRENT'";
    private static final String LOG_MINER_SQL_END_LOG_MINER = "BEGIN SYS.DBMS_LOGMNR.END_LOGMNR(); END;";
    private static final String LOG_MINER_SQL_ADD_LOG_FILES = "DECLARE\n" + "start_scn NUMBER := ?; end_scn NUMBER := ?; first_file BOOLEAN := true; \n" + "BEGIN \n" + "FOR log_file IN\n" + " (\n"
            + "  SELECT MIN(name) name, first_change# FROM \n" + "  (\n"
            + "   SELECT member AS name, first_change# FROM v$log l INNER JOIN v$logfile f ON l.group# = f.group# WHERE (l.STATUS = 'CURRENT' OR l.STATUS = 'ACTIVE') AND first_change# < end_scn\n"
            + "   UNION\n" + "   SELECT name, first_change# FROM v$archived_log WHERE name IS NOT NULL AND STANDBY_DEST='NO' AND first_change# < end_scn AND next_change# > start_scn \n"
            + "  ) group by first_change# ORDER BY first_change# \n" + " ) LOOP \n" + " IF first_file THEN\n" + "  SYS.DBMS_LOGMNR.add_logfile(log_file.name, SYS.DBMS_LOGMNR.NEW);\n"
            + "  first_file := false;\n" + " ELSE\n" + "  SYS.DBMS_LOGMNR.add_logfile(log_file.name, SYS.DBMS_LOGMNR.ADDFILE);\n" + " END IF;\n" + "END LOOP;\n" + "\n"
            + "END;";

    private static final String LOG_MINER_SQL_START_LOG_MINER = "BEGIN \n"
            + "SYS.DBMS_LOGMNR.start_logmnr( options => SYS.DBMS_LOGMNR.SKIP_CORRUPTION + SYS.DBMS_LOGMNR.NO_SQL_DELIMITER + SYS.DBMS_LOGMNR.NO_ROWID_IN_STMT + SYS.DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG);\n"
            + "END;";

    /**
     * 达梦兼容：V$LOG/V$LOGFILE 不存在；NEW=1、ADDFILE=3（达梦常量与 Oracle 不同），
     * 每轮按 [startScn, endScn) 追加匹配的归档日志并重开会话（达梦 mining 只见已加入归档存量，
     * 新提交未归档时不可见——由 LogMiner 空轮询时主动 archiveLogSwitch + 每轮重开会话解决实时性）。
     * 默认 options=16（DICT_FROM_ONLINE_CATALOG 数值）；可通过 -Ddbdatasync.dm.logminer.options=NN 调整。
     * 注意：达梦该模式下 SQL_REDO 会包含 ROWID 谓词（无等效 NO_ROWID_IN_STMT 开关），
     * ROWID 谓词统一由 LogMiner.getRedoSQL 剥离。
     */
    public static String getDmStartLogMinerSql() {
        long options = Long.getLong("dbdatasync.dm.logminer.options", 16L);
        return "BEGIN \n" + "SYS.DBMS_LOGMNR.start_logmnr( options => " + options + ");\n" + "END;";
    }

    public static void executeCallableStatement(Connection connection, String statement) throws SQLException {
        Objects.requireNonNull(statement);
        try (CallableStatement s = connection.prepareCall(statement)) {
            s.execute();
        }
    }

    public static List<BigInteger> getCurrentRedoLogSequences(Connection connection) throws SQLException {
        List<BigInteger> sequences = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(LOG_MINER_SQL_CURRENT_REDO_SEQUENCE)) {
                while (rs.next()) {
                    sequences.add(new BigInteger(rs.getString(1)));
                }
                return sequences;
            } catch (SQLException e) {
                // 兼容达梦：V$LOG 不存在；用归档日志数量作为切换指示（每次归档切换+1，比 V$RLOG.NEXT_SEQ 稳定）
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM V$ARCHIVED_LOG WHERE NAME IS NOT NULL")) {
                    if (rs.next()) {
                        sequences.add(new BigInteger(String.valueOf(rs.getInt(1))));
                    }
                    return sequences;
                } catch (SQLException e2) {
                    e2.addSuppressed(e);
                    throw e2;
                }
            }
        }
    }

    /**
     * 达梦兼容：主动切换归档，使在线 redo 中未归档的提交尽快写入归档日志。
     * 达梦 V$LOGMNR_CONTENTS 只覆盖已加入会话的归档文件存量；未归档的提交需等归档切换后才可挖掘。
     * DM8 支持 ALTER SYSTEM SWITCH LOGFILE（实测成功），Oracle 走归档进程自动切换不受影响。
     */
    public static void archiveLogSwitch(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER SYSTEM SWITCH LOGFILE");
        }
    }

    public static void endLogMiner(Connection connection) {
        if (connection != null) {
            try {
                executeCallableStatement(connection, LOG_MINER_SQL_END_LOG_MINER);
            } catch (Exception e) {
                if (e.getMessage().toUpperCase().contains("ORA-01307")) {
                    logger.info("LogMiner session was already closed", e);
                } else {
                    logger.warn("Cannot close log miner session gracefully", e);
                }
            }
        }
    }

    public static String logMinerViewQuery(String schema, String logMinerUser) {
        StringBuilder query = new StringBuilder();
        // 显式列：V$LOGMNR_CONTENTS 的 SQL_REDO/SQL_UNDO 均为 LONG，SELECT * 时 JDBC 不能可靠读取两列
        query.append("SELECT SCN, SQL_REDO, OPERATION_CODE, TIMESTAMP, XID, CSF, TABLE_NAME, SEG_OWNER ");
        query.append("FROM V$LOGMNR_CONTENTS ");
        query.append("WHERE ");
        query.append("SCN >= ? AND SCN < ? ");
        query.append("AND (");
        // MISSING_SCN/DDL only when not performed by excluded users
        query.append("(OPERATION_CODE IN (5,34) AND USERNAME NOT IN (").append(getExcludedUsers(logMinerUser)).append(")) ");
        // COMMIT/ROLLBACK
        query.append("OR (OPERATION_CODE IN (7,36)) ");
        // INSERT/UPDATE/DELETE
        query.append("OR ");
        query.append("(OPERATION_CODE IN (1,2,3) ");
        query.append(" AND SEG_OWNER NOT IN ('APPQOSSYS','AUDSYS','CTXSYS','DVSYS','DBSFWUSER','DBSNMP','GSMADMIN_INTERNAL','LBACSYS','MDSYS','OJVMSYS','OLAPSYS','ORDDATA','ORDSYS','OUTLN','SYS','SYSTEM','WMSYS','XDB') ");

        if (StringUtil.isNotBlank(schema)) {
            query.append(String.format(" AND (REGEXP_LIKE(SEG_OWNER,'^%s$','i')) ", schema));
        }

        query.append(" ))");

        return query.toString();
    }

    public static String getNextValidScnAfter() {
        StringBuilder query = new StringBuilder();

        query.append("SELECT MIN(SCN) AS NEXT_VALID_SCN ");
        query.append("FROM V$LOGMNR_CONTENTS ");
        query.append("WHERE SCN >= ? AND operation IS NOT NULL");  // 这里就是参数占位符，需要传入上一个SCN

        return query.toString();
    }

    public static String getBacklogCount() {
        StringBuilder query = new StringBuilder();

        query.append("SELECT COUNT(SCN) AS BACKLOG_COUNT ");
        query.append("FROM V$LOGMNR_CONTENTS ");
        query.append("WHERE SCN >= ? AND SCN<= ? AND operation IS NOT NULL");  // 这里就是参数占位符，需要传入上一个SCN

        return query.toString();
    }

    private static String getExcludedUsers(String logMinerUser) {
        return "'SYS','SYSTEM','" + logMinerUser.toUpperCase() + "'";
    }

    public static void setSessionParameter(Connection connection) throws SQLException {
        // 兼容达梦等 Oracle LogMiner 协议库：逐条设置 NLS 会话参数，
        // 不支持的参数（如达梦的 NLS_TIMESTAMP_FORMAT/TIME_ZONE）跳过并告警，不阻断启动
        String[] parameters = new String[]{
                "ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD HH24:MI:SS'",
                "ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF'",
                "ALTER SESSION SET NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF TZH:TZM'",
                "ALTER SESSION SET NLS_NUMERIC_CHARACTERS = '.,'",
                "ALTER SESSION SET TIME_ZONE = '00:00'",
        };
        for (String sql : parameters) {
            try {
                executeCallableStatement(connection, sql);
            } catch (SQLException e) {
                logger.warn("Skip optional NLS session parameter (not supported by current database): {} -> {}", sql, e.getMessage());
            }
        }
    }

    public static void startLogMiner(Connection connection, long startScn, long endScn) throws SQLException {
        addLogFiles(connection, startScn, endScn);
        startLogMinerSession(connection);
    }

    /**
     * 只负责把覆盖 [startScn, endScn) 的日志文件加入 LogMiner。
     * 注意：这里不会调用 START_LOGMNR，避免在 redo 切换场景下频繁重启会话导致 alert.log 暴涨。
     */
    public static void addLogFiles(Connection connection, long startScn, long endScn) throws SQLException {
        if (isDameng(connection)) {
            // 达梦不支持在匿名块中绑定变量，内联字面量；NEW=1、ADDFILE=3（达梦常量与 Oracle 不同）
            String block = "DECLARE\n"
                    + "first_file BOOLEAN := true;\n"
                    + "BEGIN\n"
                    + " FOR f IN (SELECT name, first_change# FROM v$archived_log WHERE name IS NOT NULL AND first_change# <= " + endScn
                    + " AND (next_change# > " + startScn + " OR next_change# IS NULL)) LOOP\n"
                    + "  IF first_file THEN SYS.DBMS_LOGMNR.add_logfile(f.name, 1); first_file := false; ELSE SYS.DBMS_LOGMNR.add_logfile(f.name, 3); END IF;\n"
                    + " END LOOP;\n"
                    + "END;";
            try (CallableStatement stmt = connection.prepareCall(block)) {
                stmt.execute();
            }
            return;
        }
        try (PreparedStatement addFilesStmt = connection.prepareCall(LOG_MINER_SQL_ADD_LOG_FILES)) {
            addFilesStmt.setString(1, String.valueOf(startScn));
            addFilesStmt.setString(2, String.valueOf(endScn));
            addFilesStmt.execute();
        }
    }

    /**
     * 启动 LogMiner 会话（假设日志文件已经 add 完成）。
     */
    public static void startLogMinerSession(Connection connection) throws SQLException {
        executeCallableStatement(connection, isDameng(connection) ? getDmStartLogMinerSql() : LOG_MINER_SQL_START_LOG_MINER);
    }

    /**
     * 达梦兼容判定（达梦 JDBC product name 形如 "DM DBMS"）。
     */
    public static boolean isDameng(Connection connection) throws SQLException {
        try {
            String productName = connection.getMetaData().getDatabaseProductName();
            return productName != null && productName.trim().toUpperCase().startsWith("DM");
        } catch (SQLException e) {
            return false;
        }
    }

    public static long getCurrentScn(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(LOG_MINER_SQL_GET_CURRENT_SCN)) {
                if (!rs.next()) {
                    throw new IllegalStateException("Couldn't get SCN");
                }
                return rs.getLong(1);
            } catch (SQLException e) {
                // 兼容达梦等 Oracle LogMiner 协议库：V$DATABASE 无 CURRENT_SCN 列，回退 DBMS_FLASHBACK
                try (ResultSet rs = statement.executeQuery(LOG_MINER_SQL_GET_SCN_FALLBACK)) {
                    if (!rs.next()) {
                        throw new IllegalStateException("Couldn't get SCN");
                    }
                    return rs.getLong(1);
                } catch (SQLException e2) {
                    e2.addSuppressed(e);
                    throw e2;
                }
            }
        }
    }

    /**
     * 判断当前数据库是否具备 DBMS_LOGMNR 包（达梦等版本号与 Oracle 不一致，但不能按 Oracle 版本规则拒绝）。
     */
    public static boolean hasLogMinerPackage(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(LOG_MINER_SQL_QUERY_HAS_LOGMINER_PACKAGE)) {
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
            return false;
        }
    }

    public static void setSessionContainerIfCdbMode(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(LOG_MINER_SQL_IS_CDB)) {
                rs.next();
                // cdb模式 需要切换到根容器
                if (rs.getString(1).equalsIgnoreCase("YES")) {
                    try (PreparedStatement ps = connection.prepareStatement(LOG_MINER_SQL_ALTER_SESSION_CONTAINER)) {
                        try {
                            ps.execute();
                        } catch (SQLException e) {
                            throw new OracleException(String.format("sql=%s error=%s", LOG_MINER_SQL_ALTER_SESSION_CONTAINER, e.getMessage()));
                        }
                    }
                }
            }
        }
    }

    private static List<String> queryList(Connection connection, String querySql, String key) throws SQLException {
        List<String> list = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(querySql)) {
                while (rs.next()) {
                    String k = rs.getString(key);
                    if (StringUtil.isNotBlank(k)) {
                        list.add(k.toUpperCase());
                    }
                }
            }
        }
        return list;
    }

    public static void checkPermissions(Connection connection, int version) throws SQLException {
        List<String> roles = queryList(connection, LOG_MINER_SQL_QUERY_ROLES, "GRANTED_ROLE");
        if (CollectionUtils.isEmpty(roles)) {
            throw new RuntimeException("No permissions");
        }

        // DBA
        if (roles.contains("DBA")) {
            return;
        }
        // SELECT_CATALOG_ROLE 允许用户访问数据字典视图（如 DBA_, V$, ALL_* 等），用于查询数据库的元数据
        if (!roles.contains(LOG_MINER_SELECT_CATALOG_ROLE_ROLE)) {
            throw new IllegalArgumentException(String.format("No permission, please execute sql authorization：GRANT %s TO USERNAME;", LOG_MINER_SELECT_CATALOG_ROLE_ROLE));
        }

        List<String> privileges = queryList(connection, LOG_MINER_SQL_QUERY_PRIVILEGES, "PRIVILEGE");
        if (CollectionUtils.isEmpty(privileges)) {
            throw new RuntimeException("No permissions");
        }
        List<String> checkPrivileges = version <= 11 ? LOG_MINER_ORACLE_11_PRIVILEGES_NEEDED : LOG_MINER_PRIVILEGES_NEEDED;
        long count = privileges.stream().filter(checkPrivileges::contains).count();
        if (count != checkPrivileges.size()) {
            String log = StringUtil.join(Collections.singleton(checkPrivileges), StringUtil.COMMA);
            throw new IllegalArgumentException(String.format("No permission, please execute sql authorization：GRANT %s TO USERNAME;", log));
        }
    }
}