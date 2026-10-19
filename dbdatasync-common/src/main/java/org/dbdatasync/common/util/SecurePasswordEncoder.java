/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.common.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码强哈希工具。
 *
 * <p>用于替代原弱哈希（SHA1）方案，采用业界标准的 BCrypt 算法（自带盐、抗彩虹表/暴力破解）。</p>
 *
 * <p>兼容策略：旧用户仍可能使用 SHA1 哈希存储，登录时先尝试 BCrypt 匹配，失败再回落 SHA1 并在
 * 成功后自动升级为 BCrypt，逐步完成平滑迁移。</p>
 *
 * @author DBDataSync Security
 */
public final class SecurePasswordEncoder {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private SecurePasswordEncoder() {
    }

    /**
     * 对明文密码进行 BCrypt 哈希。
     */
    public static String encode(CharSequence rawPassword) {
        return ENCODER.encode(rawPassword);
    }

    /**
     * 校验明文密码与 BCrypt 哈希是否匹配。
     */
    public static boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (StringUtil.isBlank(encodedPassword)) {
            return false;
        }
        try {
            return ENCODER.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException e) {
            // 非 BCrypt 格式（如旧 SHA1）不匹配
            return false;
        }
    }

    /**
     * 判断给定哈希串是否为 BCrypt 格式（以 $2a$/$2b$/$2y$ 开头）。
     */
    public static boolean isBcryptHash(String encodedPassword) {
        return StringUtil.isNotBlank(encodedPassword)
                && (encodedPassword.startsWith("$2a$") || encodedPassword.startsWith("$2b$") || encodedPassword.startsWith("$2y$"));
    }
}
