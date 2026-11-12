package org.dbdatasync.biz.mapping;

import org.dbdatasync.biz.BizException;
import org.dbdatasync.biz.impl.DatabaseSyncServiceImpl;
import org.dbdatasync.biz.vo.DatabaseMappingVO;
import org.dbdatasync.sdk.model.TableMapping;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Wave10 T02：{@code databaseMappingsJson} 服务端兼容归一化单测。
 *
 * <p>纯反射调用私有方法（无需 Spring / 无需连库 / 无需启服务），覆盖：
 * <ul>
 *   <li>验收 4 类形态：数组 / 空格字符串 / 空数组 / 多表多库，全部不抛异常</li>
 *   <li>根对象、tableMappings 空白字符串 / 对象 / 字符串化数组等异常形态全覆盖</li>
 *   <li>归一后继续走既有 {@code normalizeAndSortMappings()} → {@code getSortedTableMappings()} 顺序正确</li>
 *   <li>非法输入抛可读 {@link BizException} 且文案不含 {@code syntax error}</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class DatabaseMappingsNormalizeTest {

    private final DatabaseSyncServiceImpl service = new DatabaseSyncServiceImpl();

    private static final String ARRAY_FORM =
            "[{\"index\":1,\"sourceDatabase\":\"SRC\",\"sourceSchema\":\"S1\","
                    + "\"sourceConnectorId\":\"c-src\",\"targetConnectorId\":\"c-tgt\","
                    + "\"targetDatabase\":\"TGT\",\"targetSchema\":\"T1\","
                    + "\"tableMappings\":[{\"index\":1,\"sourceTable\":\"A\",\"targetTable\":\"A\"}]}]";

    private static final String MULTI_FORM =
            "[{\"index\":2,\"sourceDatabase\":\"DB2\",\"tableMappings\":["
                    + "{\"index\":2,\"sourceTable\":\"B2\",\"targetTable\":\"TB2\"},"
                    + "{\"index\":1,\"sourceTable\":\"B1\",\"targetTable\":\"TB1\"}]},"
                    + "{\"index\":1,\"sourceDatabase\":\"DB1\",\"tableMappings\":["
                    + "{\"index\":1,\"sourceTable\":\"A1\",\"targetTable\":\"TA1\"}]}]";

    @Test
    public void arrayForm_parsesFieldsCorrectly() {
        List<DatabaseMappingVO> list = parse(ARRAY_FORM);
        assertEquals(1, list.size());
        DatabaseMappingVO vo = list.get(0);
        assertEquals("SRC", vo.getSourceDatabase());
        assertEquals("S1", vo.getSourceSchema());
        assertEquals("c-src", vo.getSourceConnectorId());
        assertEquals("c-tgt", vo.getTargetConnectorId());
        assertEquals("TGT", vo.getTargetDatabase());
        assertEquals("T1", vo.getTargetSchema());
        assertEquals(1, vo.getSortedTableMappings().size());
        assertEquals("A", vo.getSortedTableMappings().get(0).getSourceTable());
        assertEquals("A", vo.getSortedTableMappings().get(0).getTargetTable());
    }

    @Test
    public void blankString_returnsEmptyList() {
        assertTrue(parse(" ").isEmpty());
        assertTrue(parse("").isEmpty());
        assertTrue(parse(null).isEmpty());
    }

    @Test
    public void emptyArray_returnsEmptyList() {
        assertTrue(parse("[]").isEmpty());
        assertTrue(parse("  [ ]  ").isEmpty());
    }

    @Test
    public void multiDbMultiTable_indexReorderKeepsOrder() {
        List<DatabaseMappingVO> list = parse(MULTI_FORM);
        assertEquals(2, list.size());
        sort(list);
        // 库映射按 index 重排：DB1(1) 在前，DB2(2) 在后
        assertEquals("DB1", list.get(0).getSourceDatabase());
        assertEquals("DB2", list.get(1).getSourceDatabase());
        assertEquals(1, list.get(0).getIndex());
        assertEquals(2, list.get(1).getIndex());
        // 表映射按 index 重排：B1 在 B2 前
        List<TableMapping> tables = list.get(1).getSortedTableMappings();
        assertEquals(2, tables.size());
        assertEquals("B1", tables.get(0).getSourceTable());
        assertEquals("TB1", tables.get(0).getTargetTable());
        assertEquals("B2", tables.get(1).getSourceTable());
        assertEquals("TB2", tables.get(1).getTargetTable());
    }

    @Test
    public void rootObject_wrappedAsSingleElement() {
        String rootObject =
                "{\"index\":1,\"sourceDatabase\":\"ROOT_DB\","
                        + "\"tableMappings\":[{\"index\":1,\"sourceTable\":\"R\",\"targetTable\":\"R\"}]}";
        List<DatabaseMappingVO> list = parse(rootObject);
        assertEquals(1, list.size());
        assertEquals("ROOT_DB", list.get(0).getSourceDatabase());
        assertEquals(1, list.get(0).getSortedTableMappings().size());
    }

    @Test
    public void tableMappingsBlankString_normalizedToEmpty() {
        List<DatabaseMappingVO> list = parse("[{\"index\":1,\"tableMappings\":\" \"}]");
        assertEquals(1, list.size());
        assertNotNull(list.get(0).getTableMappings());
        assertTrue(list.get(0).getTableMappings().isEmpty());
        assertTrue(list.get(0).getSortedTableMappings().isEmpty());
    }

    @Test
    public void tableMappingsObject_normalizedToEmpty() {
        List<DatabaseMappingVO> list = parse("[{\"index\":1,\"tableMappings\":{\"x\":1}}]");
        assertEquals(1, list.size());
        assertTrue(list.get(0).getSortedTableMappings().isEmpty());
    }

    @Test
    public void tableMappingsStringifiedArray_parsed() {
        String json = "[{\"index\":1,\"tableMappings\":"
                + "\"[{\\\"index\\\":1,\\\"sourceTable\\\":\\\"S\\\",\\\"targetTable\\\":\\\"T\\\"}]\"}]";
        List<DatabaseMappingVO> list = parse(json);
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).getSortedTableMappings().size());
        assertEquals("S", list.get(0).getSortedTableMappings().get(0).getSourceTable());
        assertEquals("T", list.get(0).getSortedTableMappings().get(0).getTargetTable());
    }

    @Test
    public void nullElements_removed() {
        List<DatabaseMappingVO> list = parse("[null,{\"index\":1,\"tableMappings\":[]}]");
        assertEquals(1, list.size());
        assertTrue(list.get(0).getSortedTableMappings().isEmpty());
    }

    @Test
    public void illegalJson_throwsReadableBizException() {
        try {
            // 畸形数组：根合法但内容不闭合，必须在服务端包装为业务异常
            parse("[{\"index\":1,\"tableMappings\":[{\"sourceTable\":\"A\"}]");
            fail("应抛出 BizException");
        } catch (BizException e) {
            assertNotNull(e.getMessage());
            assertFalse(e.getMessage().contains("syntax error"));
            assertFalse(e.getMessage().contains("offset"));
            assertTrue(e.getMessage().contains("库映射格式错误"));
        }
    }

    @Test
    public void voNormalizeTableMappings_nullAndObjectAreSafe() throws Exception {
        DatabaseMappingVO vo = new DatabaseMappingVO();
        Field field = DatabaseMappingVO.class.getDeclaredField("tableMappings");
        field.setAccessible(true);

        // null → 空列表（getSortedTableMappings 不 NPE）
        field.set(vo, null);
        vo.normalizeTableMappings();
        assertNotNull(vo.getTableMappings());
        assertTrue(vo.getSortedTableMappings().isEmpty());

        // 集合元素级兜底：字段声明为 List<TableMapping>，反射 set 必须给 List 容器
        // （Java 反射会做字段类型检查，标量/String 形态由 JSON 层 normalizeMappingsJson 归一）
        List<Object> mixed = new ArrayList<>();
        mixed.add(null);
        Map<String, Object> objectForm = new HashMap<>();
        objectForm.put("sourceTable", "OBJ");
        objectForm.put("targetTable", "TOBJ");
        objectForm.put("index", 2);
        mixed.add(objectForm);
        TableMapping row = new TableMapping();
        row.setSourceTable("S");
        row.setTargetTable("T");
        row.setIndex(1);
        mixed.add(row);
        field.set(vo, mixed);
        vo.normalizeTableMappings();
        assertEquals(2, vo.getSortedTableMappings().size());
        assertEquals("S", vo.getSortedTableMappings().get(0).getSourceTable());
        assertEquals("OBJ", vo.getSortedTableMappings().get(1).getSourceTable());

        // setter 传 null 同样归一为空列表
        vo.setTableMappings(null);
        vo.normalizeTableMappings();
        assertTrue(vo.getSortedTableMappings().isEmpty());
    }

    // ============ 反射调用私有方法 ============

    @SuppressWarnings("unchecked")
    private List<DatabaseMappingVO> parse(String json) {
        try {
            Method method = DatabaseSyncServiceImpl.class
                    .getDeclaredMethod("parseDatabaseMappings", String.class);
            method.setAccessible(true);
            return (List<DatabaseMappingVO>) method.invoke(service, json);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sort(List<DatabaseMappingVO> mappings) {
        try {
            Method method = DatabaseSyncServiceImpl.class
                    .getDeclaredMethod("normalizeAndSortMappings", List.class);
            method.setAccessible(true);
            method.invoke(service, mappings);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
