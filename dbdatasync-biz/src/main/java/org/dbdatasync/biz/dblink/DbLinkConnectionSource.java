package org.dbdatasync.biz.dblink;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * DBLink 连接来源接口：按连接器配置 ID 提供底层 JDBC 连接。
 * <p>
 * 生产实现（{@link ConnectorDbLinkConnectionSource}）经连接器框架建连；
 * 测试可注入 fake 实现，不连真库。返回的 Connection 由调用方负责关闭
 * （{@link DbLinkQueryService} 在 finally 中关闭，池化实现下 close 即归还）。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public interface DbLinkConnectionSource {

    /**
     * 打开指定连接器的 JDBC 连接。
     *
     * @param connectorId 连接器配置 ID
     * @return JDBC 连接
     * @throws SQLException 连接器不存在、非数据库类型或建连失败时抛出
     */
    Connection open(String connectorId) throws SQLException;
}
