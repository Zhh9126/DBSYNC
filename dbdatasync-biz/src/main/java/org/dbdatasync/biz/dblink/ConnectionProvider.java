package org.dbdatasync.biz.dblink;

/**
 * 数据库连接提供者接口：为 {@link DbLinkQueryService} 注入底层连接，
 * 便于引擎与连接器复用既有连接池；测试可注入 fake 实现，不建立真实连接。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public interface ConnectionProvider {

    /**
     * 提供一个可用于查询的连接。
     *
     * @return JDBC 连接
     * @throws java.sql.SQLException 获取连接失败时抛出
     */
    java.sql.Connection get() throws java.sql.SQLException;
}
