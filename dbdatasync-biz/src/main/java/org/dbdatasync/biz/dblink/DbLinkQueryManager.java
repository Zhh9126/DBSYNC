package org.dbdatasync.biz.dblink;

import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * DBLink 查询管理器（Wave20 运行时接线层）：按连接器 ID 组合
 * {@link DbLinkConnectionSource}（建连）与 {@link DbLinkQueryService}（探测/查询）。
 * <p>
 * 保守口径沿用 {@link DbLinkQueryService}：仅放行只读语句、maxRows 限行、
 * 非数据库连接器明确报错。每次查询按连接器 ID 现取现还连接（池化实现 close 即归还）。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Component("dbLinkQueryManager")
public class DbLinkQueryManager {

    @Resource
    private DbLinkConnectionSource connectionSource;

    /**
     * 探测指定连接器（源端）可用的 DBLink 列表（查询 all_db_links 视图）。
     *
     * @param connectorId 连接器配置 ID
     * @return DBLink 元数据列表（可能为空）
     * @throws SQLException 连接器不存在/非数据库类型/视图不可访问时抛出
     */
    public List<DbLinkInfo> listLinks(String connectorId) throws SQLException {
        requireConnectorId(connectorId);
        return new DbLinkQueryService(providerFor(connectorId.trim())).listDbLinks();
    }

    /**
     * 执行只读（SELECT/WITH）查询，支持 {@code @dblink} 后缀。
     *
     * @param connectorId 连接器配置 ID
     * @param sql         待执行 SQL（必须只读）
     * @param maxRows     最大返回行数，&lt;= 0 时按默认 500
     * @return 查询结果
     * @throws SQLException 执行失败时抛出；参数非法时抛 IllegalArgumentException
     */
    public DbLinkQueryResult query(String connectorId, String sql, int maxRows) throws SQLException {
        requireConnectorId(connectorId);
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        return new DbLinkQueryService(providerFor(connectorId.trim())).query(sql, maxRows);
    }

    /**
     * 参数校验。
     *
     * @param connectorId 连接器 ID
     */
    private static void requireConnectorId(String connectorId) {
        if (connectorId == null || connectorId.trim().isEmpty()) {
            throw new IllegalArgumentException("connectorId 不能为空");
        }
    }

    /**
     * 按连接器 ID 构造 ConnectionProvider。
     *
     * @param connectorId 已 trim 的连接器 ID
     * @return ConnectionProvider
     */
    private ConnectionProvider providerFor(final String connectorId) {
        return new ConnectionProvider() {
            @Override
            public Connection get() throws SQLException {
                return connectionSource.open(connectorId);
            }
        };
    }
}
