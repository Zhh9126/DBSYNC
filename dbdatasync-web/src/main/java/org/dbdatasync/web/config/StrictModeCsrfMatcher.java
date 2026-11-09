package org.dbdatasync.web.config;

import org.springframework.security.web.util.matcher.RequestMatcher;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Wave24：CSRF 保护范围匹配器（严格安全模式下生效）。
 *
 * <p>直接全局开启 Spring Security CSRF 会让存量页面的所有 AJAX POST 立刻 403
 * （这些页面历史上不含 _csrf 字段）。因此保护范围收敛到「有副作用的管理动作」，
 * 且只在严格安全模式开启时生效；登录、开放 API、静态资源和只读请求不校验。
 * 前端由 {@code /js/security-headers.js} 统一从 XSRF-TOKEN cookie 注入
 * {@code X-XSRF-TOKEN} 头，存量页面无需逐个改造。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class StrictModeCsrfMatcher implements RequestMatcher {

    /** 需要 CSRF token 的管理动作 */
    private static final List<String> PROTECTED_PREFIXES = Arrays.asList(
            "/system/", "/user/", "/schema-history/confirm", "/schema-history/unblock",
            "/cutover/execute", "/cutover/rollback", "/batch-profile/reset");

    private final WebSecurityPolicy policy;

    public StrictModeCsrfMatcher(WebSecurityPolicy policy) {
        this.policy = policy;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        if (!policy.isStrict()) {
            return false;
        }
        String method = request.getMethod();
        if (method == null || "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method) || "TRACE".equalsIgnoreCase(method)) {
            return false;
        }
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI().toLowerCase(Locale.ROOT);
        // 登录与开放 API 不受 CSRF 约束（登录本身即为凭据交换，开放 API 使用签名鉴权）
        if (uri.endsWith("/login") || uri.startsWith("/openapi/")) {
            return false;
        }
        for (String prefix : PROTECTED_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
