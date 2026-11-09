package org.dbdatasync.web.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wave24：RBAC 角色权限体系。
 *
 * <p>Wave23 只有「是否 admin」的单一表达式，没有角色与 URL 权限矩阵，普通用户
 * 登录后可以调用全部写操作。本类定义三种角色与可查询的权限矩阵，并通过 Spring
 * Security SpEL 在每次请求时求值（{@code @rbacPolicy.check(authentication,request)}）：</p>
 *
 * <ul>
 *     <li>{@code admin}：全部权限；</li>
 *     <li>{@code operator / user}：运行、观察、确认类操作，不可改系统配置/用户/actuator；</li>
 *     <li>{@code viewer}：只读；写操作一律拒绝（只读查询端点白名单除外）。</li>
 * </ul>
 *
 * <p>兼容策略：严格安全模式关闭时所有已登录用户放行（与存量部署完全一致），
 * 开启后矩阵才生效，避免升级即锁死运维。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
@Component("rbacPolicy")
public class RbacPolicy {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_OPERATOR = "operator";
    public static final String ROLE_VIEWER = "viewer";

    /** 仅管理员可访问的路径前缀（严格模式） */
    private static final List<String> ADMIN_ONLY_PREFIXES = Arrays.asList(
            "/actuator", "/system", "/user");

    /** 只读角色允许调用的查询型 POST 端点（页面普遍用 POST 做查询） */
    private static final List<String> READ_ONLY_POST_ENDPOINTS = Arrays.asList(
            "/report", "/records", "/list", "/state", "/summary", "/taskReport",
            "/executionLog", "/cluster", "/deadLetters", "/prometheus", "/version.json",
            "/config", "/rollbackPlan", "/shadow/flipSafe");

    private final WebSecurityPolicy webSecurityPolicy;

    public RbacPolicy(WebSecurityPolicy webSecurityPolicy) {
        this.webSecurityPolicy = webSecurityPolicy;
    }

    /** Spring Security 表达式入口 */
    public boolean check(Authentication authentication, HttpServletRequest request) {
        if (authentication == null || !authentication.isAuthenticated() || request == null) {
            return false;
        }
        if (!webSecurityPolicy.isStrict()) {
            return true;
        }
        String role = resolveRole(authentication);
        return allows(role, request.getRequestURI(), request.getMethod());
    }

    /** 角色判定（纯函数，可离线单测） */
    public boolean allows(String role, String path, String method) {
        String normalized = normalizeRole(role);
        if (ROLE_ADMIN.equals(normalized)) {
            return true;
        }
        final String uri = path == null ? "" : path.toLowerCase(Locale.ROOT);
        final String verb = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        for (String prefix : ADMIN_ONLY_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return false;
            }
        }
        boolean mutating = !"GET".equals(verb) && !"HEAD".equals(verb) && !"OPTIONS".equals(verb);
        if (ROLE_VIEWER.equals(normalized)) {
            if (!mutating) {
                return true;
            }
            for (String allowed : READ_ONLY_POST_ENDPOINTS) {
                if (uri.endsWith(allowed)) {
                    return true;
                }
            }
            return false;
        }
        return ROLE_OPERATOR.equals(normalized);
    }

    private String resolveRole(Authentication authentication) {
        String role = ROLE_OPERATOR;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = normalizeRole(authority.getAuthority());
            if (ROLE_ADMIN.equals(value)) {
                return ROLE_ADMIN;
            }
            if (ROLE_VIEWER.equals(value)) {
                role = ROLE_VIEWER;
            }
        }
        return role;
    }

    /** 兼容 user / ROLE_ADMIN / ADMIN 等历史写法 */
    public static String normalizeRole(String raw) {
        if (raw == null) {
            return ROLE_OPERATOR;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("role_")) {
            value = value.substring(5);
        }
        if ("user".equals(value)) {
            return ROLE_OPERATOR;
        }
        if ("admin".equals(value)) {
            return ROLE_ADMIN;
        }
        if ("viewer".equals(value)) {
            return ROLE_VIEWER;
        }
        if ("operator".equals(value)) {
            return ROLE_OPERATOR;
        }
        return ROLE_OPERATOR;
    }

    /** 权限矩阵（供 /rbac/policy 与运维页展示） */
    public Map<String, Object> policy() {
        Map<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("strictMode", webSecurityPolicy.isStrict());
        matrix.put("roles", Arrays.asList(ROLE_ADMIN, ROLE_OPERATOR, ROLE_VIEWER));
        matrix.put("adminOnlyPrefixes", Collections.unmodifiableList(ADMIN_ONLY_PREFIXES));
        matrix.put("viewerReadOnlyPostEndpoints", Collections.unmodifiableList(READ_ONLY_POST_ENDPOINTS));
        matrix.put("description", "严格安全模式下按角色矩阵逐请求鉴权；关闭时所有已登录用户放行");
        return matrix;
    }
}
