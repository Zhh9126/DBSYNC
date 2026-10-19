/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.sdk.connector.database;

import org.dbdatasync.common.util.StringUtil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 日志脱敏工具。
 *
 * <p>在 debug 级别打印 SQL 时，对可能包含敏感信息的赋值（password/pwd/secret/token 等）做掩码，
 * 避免凭据泄露到日志文件。</p>
 */
public final class SqlLogMasker {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|apikey|api_key|access_key|authorization)\\s*=\\s*'[^']*'");

    private static final String MASK = "***";

    private SqlLogMasker() {
    }

    public static String mask(String sql) {
        if (StringUtil.isBlank(sql)) {
            return sql;
        }
        Matcher m = SENSITIVE.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int eq = m.group().indexOf('=');
            m.appendReplacement(sb, m.group().substring(0, eq + 1) + " '" + MASK + "'");
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
