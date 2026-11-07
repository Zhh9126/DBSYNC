package org.dbdatasync.biz.dblink;

/**
 * DBLink 元数据条目：对应 Oracle {@code all_db_links} 视图的一行。
 * <p>纯 POJO，字段均可为 null（视实现数据库返回列而定）。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class DbLinkInfo {

    /** 属主（all_db_links.OWNER）。 */
    private String owner;

    /** 链接名（all_db_links.DB_LINK，通常含域名后缀）。 */
    private String dbLink;

    /** 远端用户名（all_db_links.USERNAME）。 */
    private String username;

    /** 远端主机（all_db_links.HOST，TNS 别名或连接串）。 */
    private String host;

    public DbLinkInfo() {
    }

    /**
     * 全参构造。
     *
     * @param owner    属主
     * @param dbLink   链接名
     * @param username 远端用户名
     * @param host     远端主机
     */
    public DbLinkInfo(String owner, String dbLink, String username, String host) {
        this.owner = owner;
        this.dbLink = dbLink;
        this.username = username;
        this.host = host;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getDbLink() {
        return dbLink;
    }

    public void setDbLink(String dbLink) {
        this.dbLink = dbLink;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }
}
