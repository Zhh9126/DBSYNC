package org.dbdatasync.sdk.util;

import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.sdk.checkpoint.IdempotencyKey;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 无主键表的行标识退化策略（Wave23）。
 *
 * <p>优先级：主键 → 唯一索引/唯一约束列 → 全行哈希。前两者可直接用于目标端
 * UPDATE/DELETE 的 WHERE 条件；全行哈希只在「识别/校验/告警」层可用，
 * 因为目标表并不存在哈希列，直接拿它拼 SQL 会写错语句，所以本版本
 * <b>不会把哈希列伪装成主键</b>返回给写入链路。</p>
 */
public final class RowIdentityResolver {

    public enum Strategy {
        PRIMARY_KEY, UNIQUE_KEY, FULL_ROW_HASH, NONE
    }

    public static final class RowIdentity {
        private final Strategy strategy;
        private final List<String> keyFields;

        public RowIdentity(Strategy strategy, List<String> keyFields) {
            this.strategy = strategy;
            this.keyFields = keyFields == null
                    ? Collections.<String>emptyList() : Collections.unmodifiableList(keyFields);
        }

        public Strategy getStrategy() { return strategy; }
        public List<String> getKeyFields() { return keyFields; }
        public boolean isUsableAsWhereKey() {
            return (strategy == Strategy.PRIMARY_KEY || strategy == Strategy.UNIQUE_KEY)
                    && !keyFields.isEmpty();
        }
    }

    private static volatile boolean fallbackEnabled = true;
    /** Wave23：由业务层安装的动态配置源（系统配置页保存即时生效） */
    private static volatile Supplier<Boolean> fallbackProvider;

    private RowIdentityResolver() {
    }

    public static void setFallbackEnabled(boolean enabled) {
        fallbackEnabled = enabled;
    }

    public static void installFallbackProvider(Supplier<Boolean> provider) {
        fallbackProvider = provider;
    }

    public static void uninstallFallbackProvider() {
        fallbackProvider = null;
    }

    public static boolean isFallbackEnabled() {
        Supplier<Boolean> provider = fallbackProvider;
        if (provider != null) {
            try {
                Boolean value = provider.get();
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
                // 配置源异常时退回静态设置，绝不阻断同步
            }
        }
        return fallbackEnabled;
    }

    public static RowIdentity resolve(Table table) {
        if (table == null) {
            return new RowIdentity(Strategy.NONE, Collections.<String>emptyList());
        }
        List<Field> pks = PrimaryKeyUtil.findPrimaryKeyFields(table.getColumn());
        if (!CollectionUtils.isEmpty(pks)) {
            return new RowIdentity(Strategy.PRIMARY_KEY, names(pks));
        }
        List<String> unique = findUniqueKeyFields(table);
        if (!unique.isEmpty()) {
            return new RowIdentity(Strategy.UNIQUE_KEY, unique);
        }
        if (!CollectionUtils.isEmpty(table.getColumn())) {
            return new RowIdentity(Strategy.FULL_ROW_HASH, Collections.<String>emptyList());
        }
        return new RowIdentity(Strategy.NONE, Collections.<String>emptyList());
    }

    /** 从 Table/Field 的扩展信息里读取唯一索引列（各连接器元数据填充约定） */
    public static List<String> findUniqueKeyFields(Table table) {
        List<String> result = new ArrayList<String>();
        if (table == null) {
            return result;
        }
        if (table.getExtInfo() != null) {
            String configured = table.getExtInfo().getProperty("uniqueColumns");
            if (configured != null) {
                for (String part : configured.split("[,;]")) {
                    String name = part.trim();
                    if (!name.isEmpty() && !result.contains(name)) {
                        result.add(name);
                    }
                }
            }
        }
        if (!CollectionUtils.isEmpty(table.getColumn())) {
            for (Field field : table.getColumn()) {
                if (field == null || field.getExtInfo() == null) {
                    continue;
                }
                String flag = field.getExtInfo().getProperty("unique");
                if (flag == null) {
                    flag = field.getExtInfo().getProperty("uniqueKey");
                }
                if ("true".equalsIgnoreCase(flag) && !result.contains(field.getName())) {
                    result.add(field.getName());
                }
            }
        }
        return result;
    }

    /** 全行稳定哈希，用于无主键表的一致性校验与重复识别 */
    public static String fullRowHash(Map<String, Object> row) {
        return IdempotencyKey.rowDigest(row);
    }

    private static List<String> names(List<Field> fields) {
        List<String> names = new ArrayList<String>(fields.size());
        for (Field f : fields) {
            if (f != null && f.getName() != null) {
                names.add(f.getName());
            }
        }
        return names;
    }
}
