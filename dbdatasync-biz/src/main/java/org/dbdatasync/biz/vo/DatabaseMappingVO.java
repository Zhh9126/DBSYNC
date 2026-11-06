/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.vo;

import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.sdk.model.DatabaseMapping;
import org.dbdatasync.sdk.model.TableMapping;
import org.springframework.beans.BeanUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 库映射业务视图：库维字段继承 {@link DatabaseMapping}，表映射仅用于接口入参/回显。
 *
 * @author wuji
 * @version 1.0.0
 * @date 2026-07-23 10:45
 */
public final class DatabaseMappingVO extends DatabaseMapping {

    /**
     * 表映射（源表 -&gt; 目标表），对应 DBDataSync_table_group，不写入 task.JSON
     */
    private List<TableMapping> tableMappings = new ArrayList<>();

    public List<TableMapping> getTableMappings() {
        return tableMappings;
    }

    public void setTableMappings(List<TableMapping> tableMappings) {
        this.tableMappings = tableMappings == null ? new ArrayList<>() : tableMappings;
    }

    /**
     * 按 index 升序返回表映射列表（副本）。
     */
    public List<TableMapping> getSortedTableMappings() {
        if (tableMappings == null || tableMappings.isEmpty()) {
            return Collections.emptyList();
        }
        return tableMappings.stream()
                .sorted(Comparator.comparingInt(TableMapping::getIndex))
                .collect(Collectors.toList());
    }

    /**
     * 表映射归一化（Wave10 P0-2 双端数组契约的 VO 层兜底）。
     *
     * <p>把字段值统一收敛为 {@code List<TableMapping>}：字段 {@code null} → 空列表；
     * 集合中的 null 元素剔除，Map/JSONObject 元素转成 {@link TableMapping}；
     * 若上游防御性地塞入字符串化数组文本（如 {@code "[{\"sourceTable\":\"A\"}]"}）则解析成列表，
     * 空白字符串 / 对象 / 标量 → 空列表。正常数组路径内容与顺序不变。</p>
     *
     * <p>注意：字段声明为 {@code List<TableMapping>}，Java 反射与 fastjson2 都会在赋值时做类型
     * 检查，因此「根对象 / tableMappings 标量」等形态<strong>必须</strong>在 JSON 层由
     * {@code DatabaseSyncServiceImpl.normalizeMappingsJson()} 先行归一；本方法承担解析后的
     * 元素级兜底与 getSortedTableMappings() 的 NPE 防护。字段类型与
     * {@link #getSortedTableMappings()} 语义一行不改。</p>
     */
    public void normalizeTableMappings() {
        this.tableMappings = toTableMappingList(readTableMappingsRaw());
    }

    /** 反射读取字段运行时值，兼容极端形态（被反射/自定义反序列化塞入的非 List 值） */
    private Object readTableMappingsRaw() {
        try {
            Field field = DatabaseMappingVO.class.getDeclaredField("tableMappings");
            field.setAccessible(true);
            return field.get(this);
        } catch (ReflectiveOperationException e) {
            return this.tableMappings;
        }
    }

    private static List<TableMapping> toTableMappingList(Object raw) {
        List<TableMapping> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.isEmpty() || !text.startsWith("[")) {
                return result;
            }
            try {
                List<TableMapping> parsed = JsonUtil.jsonToArray(text, TableMapping.class);
                if (parsed != null) {
                    for (TableMapping item : parsed) {
                        if (item != null) {
                            result.add(item);
                        }
                    }
                }
            } catch (RuntimeException ignore) {
                return new ArrayList<>();
            }
            return result;
        }
        if (raw instanceof Map) {
            // 对象形态不视为单条表映射，避免误导
            return result;
        }
        if (raw instanceof Collection) {
            for (Object item : (Collection<?>) raw) {
                TableMapping mapping = toTableMapping(item);
                if (mapping != null) {
                    result.add(mapping);
                }
            }
        }
        return result;
    }

    private static TableMapping toTableMapping(Object item) {
        if (item instanceof TableMapping) {
            return (TableMapping) item;
        }
        if (item instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) item;
            TableMapping mapping = new TableMapping();
            mapping.setSourceTable(str(map.get("sourceTable")));
            mapping.setTargetTable(str(map.get("targetTable")));
            mapping.setIndex(intOf(map.get("index")));
            return mapping;
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    /**
     * 从库映射领域模型复制库维字段。
     */
    public static DatabaseMappingVO from(DatabaseMapping mapping) {
        DatabaseMappingVO vo = new DatabaseMappingVO();
        if (mapping != null) {
            BeanUtils.copyProperties(mapping, vo);
        }
        return vo;
    }

    /**
     * 转为可持久化的库映射（不含表）。
     */
    public DatabaseMapping toDatabaseMapping() {
        DatabaseMapping mapping = new DatabaseMapping();
        BeanUtils.copyProperties(this, mapping);
        return mapping;
    }
}
