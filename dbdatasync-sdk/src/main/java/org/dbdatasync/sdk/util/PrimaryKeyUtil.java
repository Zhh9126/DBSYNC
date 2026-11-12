/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.sdk.util;

import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.NumberUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.sdk.SdkException;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.Table;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class PrimaryKeyUtil {

    /**
     * 返回主键名称
     *
     * @param table
     * @return
     */
    public static List<String> findTablePrimaryKeys(Table table) {
        if (null == table) {
            throw new SdkException("The table is null.");
        }

        // 获取表同步的主键字段
        List<String> primaryKeys = findPrimaryKeyFields(table.getColumn()).stream().map(Field::getName).collect(Collectors.toList());

        // 如果存在表字段映射关系，没有配置主键则抛出异常提示
        if (!CollectionUtils.isEmpty(table.getColumn()) && CollectionUtils.isEmpty(primaryKeys)) {
            // Wave23：无主键表优先退化到唯一索引列（可直接作为 WHERE 条件）
            if (RowIdentityResolver.isFallbackEnabled()) {
                RowIdentityResolver.RowIdentity identity = RowIdentityResolver.resolve(table);
                if (identity.getStrategy() == RowIdentityResolver.Strategy.UNIQUE_KEY) {
                    return identity.getKeyFields();
                }
                throw new SdkException(String.format(
                        "表 %s 缺少主键与唯一索引；全行哈希策略可用于校验，但不能直接生成目标端 WHERE 条件，请为表补充主键或唯一索引.",
                        table.getName()));
            }
            throw new SdkException(String.format("表 %s 缺少主键.", table.getName()));
        }
        return primaryKeys;
    }

    /**
     * 返回主键属性字段集合
     *
     * @param fields
     * @return
     */
    public static List<Field> findExistPrimaryKeyFields(List<Field> fields) {
        List<Field> list = findPrimaryKeyFields(fields);
        if (CollectionUtils.isEmpty(list)) {
            throw new SdkException("主键为空");
        }
        return list;
    }

    /**
     * 返回主键属性字段集合
     *
     * @param fields
     * @return
     */
    public static List<Field> findPrimaryKeyFields(List<Field> fields) {
        List<Field> list = new ArrayList<>();
        if (!CollectionUtils.isEmpty(fields)) {
            Set<String> mark = new HashSet<>();
            fields.forEach(f-> {
                if (f.isPk() && !mark.contains(f.getName())) {
                    list.add(f);
                    mark.add(f.getName());
                }
            });
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 游标主键必须为可比较类型（数字、字符、日期时间等），否则会导致分页失效
     * 
     * <p>支持的类型：
     * <ul>
     *   <li>数字类型：NUMERIC, BIGINT, INTEGER, TINYINT, SMALLINT</li>
     *   <li>字符类型：VARCHAR, CHAR, NCHAR, NVARCHAR, LONGVARCHAR（字典序比较）</li>
     *   <li>日期时间类型：DATE, TIME, TIMESTAMP（按时间顺序比较）</li>
     * </ul>
     * </p>
     */
    public static boolean isSupportedCursor(List<Field> fields) {
        if (CollectionUtils.isEmpty(fields)) {
            return false;
        }

        Map<String, Integer> typeAliases = new HashMap<>();
        fields.forEach(field-> {
            if (field.isPk()) {
                typeAliases.put(field.getName(), field.getType());
            }
        });

        for (Integer pkType : typeAliases.values()) {
            if (!isSupportedCursorType(pkType)) {
                return false;
            }
        }
        return !CollectionUtils.isEmpty(typeAliases);
    }

    /**
     * 是否支持游标类型
     * 
     * <p>支持可比较类型，包括：
     * <ul>
     *   <li>数字类型：可直接使用 > 比较</li>
     *   <li>字符类型：使用字典序比较</li>
     *   <li>日期时间类型：按时间顺序比较</li>
     * </ul>
     * </p>
     * <p>注意：不支持 CLOB/NCLOB/BLOB 等大对象类型作为游标。</p>
     *
     * @param type JDBC类型
     * @return 是否支持
     */
    private static boolean isSupportedCursorType(Integer type) {
        // 数字类型（支持直接比较）
        if (type == Types.NUMERIC || type == Types.BIGINT || type == Types.INTEGER || type == Types.TINYINT || type == Types.SMALLINT || type == Types.DECIMAL || type == Types.FLOAT
                || type == Types.DOUBLE || type == Types.REAL) {
            return true;
        }
        // 字符类型（支持字典序比较）
        if (type == Types.VARCHAR || type == Types.CHAR || type == Types.NCHAR || type == Types.NVARCHAR || type == Types.LONGVARCHAR) {
            return true;
        }
        // 日期时间类型（支持时间顺序比较）
        if (type == Types.DATE || type == Types.TIME || type == Types.TIMESTAMP) {
            return true;
        }
        return false;
    }

    /**
     * 获取最新游标值
     *
     * @param data 数据列表
     * @param primaryKeys 主键字段名列表
     * @return 游标值数组，如果数据为空或主键列表为空则返回null
     */
    public static Object[] getLastCursors(List<Map> data, List<String> primaryKeys) {
        if (CollectionUtils.isEmpty(data) || CollectionUtils.isEmpty(primaryKeys)) {
            return null;
        }
        Map last = data.get(data.size() - 1);
        if (last == null || last.isEmpty()) {
            return null;
        }

        Object[] cursors = new Object[primaryKeys.size()];
        int i = 0;
        for (String pk : primaryKeys) {
            cursors[i++] = last.get(pk);
        }
        return cursors;
    }

    /**
     * Wave30：结构化位点编码前缀。旧实现用 {@code StringUtil.join(cursors, ",")} 保存、
     * {@code split(",")} 恢复，主键值含逗号会把复合主键拆错，纯数字字符串（如 "00123"）
     * 又被强转成 Long 丢掉前导零，时间戳类型也退化为字符串。
     */
    public static final String CURSOR_ENCODE_PREFIX = "DBDS-CURSOR-V2:";

    private static final String CHARSET = "UTF-8";

    /**
     * 把游标值编码为带类型的单行串；值经 URL 编码，逗号与类型标记都不会与值本身冲突。
     *
     * @param cursors 游标值数组，null / 空数组返回空串
     * @return 形如 {@code DBDS-CURSOR-V2:s:a%2Cb,l:123,ts:2026-01-01+00%3A00%3A00.0} 的串
     */
    public static String encodeCursors(Object[] cursors) {
        if (cursors == null || cursors.length == 0) {
            return StringUtil.EMPTY;
        }
        StringBuilder sb = new StringBuilder(CURSOR_ENCODE_PREFIX);
        for (int i = 0; i < cursors.length; i++) {
            if (i > 0) {
                sb.append(StringUtil.COMMA);
            }
            Object v = cursors[i];
            sb.append(cursorTag(v)).append(':').append(encodeValue(v == null ? "" : String.valueOf(v)));
        }
        return sb.toString();
    }

    /**
     * 恢复游标位点：优先识别 V2 结构化格式，兼容旧版逗号拼接串。
     * 旧格式里带前导零的数字串保持字符串，不再误转 Long 导致字典序变数值序、续传漏行。
     */
    public static Object[] getLastCursors(String str) {
        if (StringUtil.isBlank(str)) {
            return null;
        }
        if (str.startsWith(CURSOR_ENCODE_PREFIX)) {
            String body = str.substring(CURSOR_ENCODE_PREFIX.length());
            String[] parts = body.split(StringUtil.COMMA, -1);
            Object[] cursors = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) {
                int colon = parts[i].indexOf(':');
                String tag = colon < 0 ? "s" : parts[i].substring(0, colon);
                String raw = decodeValue(colon < 0 ? parts[i] : parts[i].substring(colon + 1));
                cursors[i] = restoreCursor(tag, raw);
            }
            return cursors;
        }
        String[] split = StringUtil.split(str, StringUtil.COMMA);
        Object[] cursors = new Object[split.length];
        for (int i = 0; i < split.length; i++) {
            String val = split[i];
            cursors[i] = NumberUtil.isCreatable(val) && !hasLeadingZero(val) ? NumberUtil.toLong(val) : val;
        }
        return cursors;
    }

    private static String cursorTag(Object v) {
        if (v == null) {
            return "n";
        }
        if (v instanceof CharSequence || v instanceof Character) {
            return "s";
        }
        if (v instanceof Byte || v instanceof Short || v instanceof Integer
                || v instanceof Long || v instanceof BigInteger) {
            return "l";
        }
        if (v instanceof BigDecimal || v instanceof Float || v instanceof Double) {
            return "d";
        }
        if (v instanceof Timestamp) {
            return "ts";
        }
        if (v instanceof java.sql.Date) {
            return "dt";
        }
        if (v instanceof Time) {
            return "tm";
        }
        return "o";
    }

    private static Object restoreCursor(String tag, String raw) {
        try {
            if ("n".equals(tag)) {
                return null;
            }
            if ("l".equals(tag)) {
                return Long.valueOf(raw);
            }
            if ("d".equals(tag)) {
                return new BigDecimal(raw);
            }
            if ("ts".equals(tag)) {
                return Timestamp.valueOf(raw);
            }
            if ("dt".equals(tag)) {
                return java.sql.Date.valueOf(raw);
            }
            if ("tm".equals(tag)) {
                return Time.valueOf(raw);
            }
        } catch (Exception ignore) {
            // 单个值类型还原失败时降级为字符串，绝不中断续传
        }
        return raw;
    }

    private static boolean hasLeadingZero(String val) {
        String digits = val.startsWith("-") ? val.substring(1) : val;
        return digits.length() > 1 && digits.charAt(0) == '0' && Character.isDigit(digits.charAt(1));
    }

    private static String encodeValue(String text) {
        try {
            return URLEncoder.encode(text, CHARSET);
        } catch (UnsupportedEncodingException e) {
            return text.replace(StringUtil.COMMA, "%2C");
        }
    }

    private static String decodeValue(String text) {
        try {
            return URLDecoder.decode(text, CHARSET);
        } catch (Exception e) {
            return text;
        }
    }

}