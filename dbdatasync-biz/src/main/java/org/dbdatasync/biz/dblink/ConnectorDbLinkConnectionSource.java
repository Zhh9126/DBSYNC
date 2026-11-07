package org.dbdatasync.biz.dblink;

import org.dbdatasync.connector.base.ConnectorFactory;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.model.Connector;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.connector.database.DatabaseConnectorInstance;
import org.dbdatasync.sdk.model.ConnectorConfig;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 基于连接器框架的 {@link DbLinkConnectionSource} 生产实现。
 * <p>
 * 建连样板与 {@code ConnectorServiceImpl.getSchema(id, database)} 一致：
 * 缓存优先（{@code connectorFactory.connect(instanceId)}），失败现场重建
 * （{@code connectorFactory.connect(instanceId, config, catalog, schema)}）。
 * 仅数据库类连接器（{@link DatabaseConnectorInstance}）可提供 Connection。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Component("dbLinkConnectionSource")
public class ConnectorDbLinkConnectionSource implements DbLinkConnectionSource {

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private ConnectorFactory connectorFactory;

    @Override
    public Connection open(String connectorId) throws SQLException {
        if (connectorId == null || connectorId.trim().isEmpty()) {
            throw new IllegalArgumentException("connectorId 不能为空");
        }
        Connector connector = profileComponent.getConnector(connectorId.trim());
        if (connector == null) {
            throw new SQLException("连接器不存在: " + connectorId);
        }
        ConnectorConfig config = connector.getConfig();
        try {
            ConnectorInstance instance;
            try {
                instance = connectorFactory.connect(connector.getId());
            } catch (Exception e) {
                // 应用重启后连接池为空（或该连接器尚未被任务启动过）：现场建连
                instance = connectorFactory.connect(connector.getId(), config,
                        StringUtil.EMPTY, StringUtil.EMPTY);
            }
            if (instance instanceof DatabaseConnectorInstance) {
                return ((DatabaseConnectorInstance) instance).getConnection();
            }
            throw new SQLException("连接器不是数据库类型，无法执行 DBLink 查询: " + connectorId);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("DBLink 建连失败: " + e.getMessage(), e);
        }
    }
}
