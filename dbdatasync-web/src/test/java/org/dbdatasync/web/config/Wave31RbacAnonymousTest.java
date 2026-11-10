package org.dbdatasync.web.config;

import org.junit.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wave31-fix 回归：匿名 Token 不得通过鉴权。
 *
 * <p>AnonymousAuthenticationToken.isAuthenticated() 恒为 true，旧实现只判
 * {@code isAuthenticated()}，导致未登录请求（假会话/无会话）被当作已认证放行，
 * 实测假 JSESSIONID 可调用任意业务接口。修复后匿名 Token 一律拒绝，会话失效
 * 回归「302 到登录页」语义。</p>
 */
public class Wave31RbacAnonymousTest {

    private final RbacPolicy policy = new RbacPolicy(new WebSecurityPolicy(false));

    /** 无 Mockito/spring-test 依赖，用 JDK 动态代理生成 HttpServletRequest 桩 */
    private final HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{HttpServletRequest.class},
            (p, m, a) -> null);

    private Authentication anon() {
        return new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }

    @Test
    public void anonymousTokenMustBeDenied() {
        assertFalse("匿名 Token 必须拒绝",
                policy.check(anon(), request));
    }

    @Test
    public void nullAuthMustBeDenied() {
        assertFalse(policy.check(null, request));
    }

    @Test
    public void loggedInUserAllowedWhenStrictOff() {
        Authentication auth = new TestingAuthenticationToken("admin", "n/a", "ROLE_admin");
        assertTrue("严格模式关闭时已登录用户放行",
                policy.check(auth, request));
    }

    @Test
    public void authenticatedFakePrincipalAllowedWhenStrictOff() {
        // 已认证 Token（如真实登录会话）在非严格模式下放行
        Authentication auth = new TestingAuthenticationToken("operator", "n/a", "ROLE_operator");
        assertTrue(policy.check(auth, request));
    }
}
