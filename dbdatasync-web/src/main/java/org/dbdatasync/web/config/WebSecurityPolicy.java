/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.web.config;

import org.dbdatasync.biz.SystemConfigService;
import org.dbdatasync.parser.model.SystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * Wave23 运行期安全策略。
 *
 * <p>{@code WebAppConfig.configure(HttpSecurity)} 只在应用启动时执行一次，
 * 若把系统配置页的「严格安全模式」写死成表达式字符串，页面保存后不会生效。
 * 因此这里把开关做成一个可被安全表达式每次请求求值的 Bean 方法：</p>
 *
 * <pre>
 * isAuthenticated() and (!&#64;webSecurityPolicy.isStrict()
 *         or hasAnyAuthority('admin','ADMIN','ROLE_ADMIN'))
 * </pre>
 *
 * <p>关闭时等价于原来的 {@code authenticated()}，存量部署零影响；
 * 开启后 /actuator/**、/system/**、/user/** 仅 admin 可访问。
 * 配置读取异常按关闭处理，避免配置存储故障把管理员锁在系统外。</p>
 */
@Component("webSecurityPolicy")
public class WebSecurityPolicy {

    private static final Logger logger = LoggerFactory.getLogger(WebSecurityPolicy.class);

    /** 启动参数基线，优先级高于页面配置（运维可通过环境变量强制启用） */
    private final boolean strictByProperty;

    @Resource
    private SystemConfigService systemConfigService;

    public WebSecurityPolicy(@Value("${dbdatasync.web.security.strict:false}") boolean strictByProperty) {
        this.strictByProperty = strictByProperty;
    }

    public boolean isStrict() {
        if (strictByProperty) {
            return true;
        }
        try {
            SystemConfig config = systemConfigService == null ? null : systemConfigService.getSystemConfig();
            return config != null && config.isEnableStrictSecurity();
        } catch (Exception e) {
            logger.debug("严格安全模式读取失败，按关闭处理: {}", e.getMessage());
            return false;
        }
    }
}
