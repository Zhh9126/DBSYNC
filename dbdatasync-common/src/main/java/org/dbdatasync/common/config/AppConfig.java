/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2022/5/18 0:04
 */
@Configuration
@ConfigurationProperties(prefix = "info.app")
public class AppConfig {

    private String name = "数据同步工具";

    private String version;

    private String copyright;

    private String company = "";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getCopyright() {
        return "";
    }

    public void setCopyright(String copyright) {
        this.copyright = copyright;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }
}
