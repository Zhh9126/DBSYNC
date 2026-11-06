package org.dbdatasync.biz.perfadvice;

import org.dbdatasync.biz.service.impl.DatabaseSyncPerfAdviceServiceImpl;
import org.dbdatasync.biz.vo.DatabaseSyncPerfAdviceVO;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.TaskDetailProfile;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.model.Table;
import org.dbdatasync.sdk.spi.TaskService;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave10 T01：perfAdvice 唯一行数口径单测。
 *
 * <p>纯 JDK 动态代理桩（不连库、不启服务、不写真实数据）。覆盖：
 * <ul>
 *   <li>7 个数值字段：totalRows/successRows/failRows/durationMs/throughputPerSecond/bigTables/diffTables</li>
 *   <li>Meta.total 与 成功+失败 不一致时核对字段与结论文案</li>
 *   <li>空任务 / 未迁移 / 明细 JSON 解析失败 3 类降级不 NPE</li>
 *   <li>同任务两次 analyze 数值稳定</li>
 *   <li>结论不再出现「数据总量与差异均为 0」</li>
 * </ul>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-06
 */
public class DatabaseSyncPerfAdviceServiceImplTest {

    private static final String TASK_ID = "task-wave10-1001";

    private static final String BIG_DATA =
            "{\"sourceTotal\":2000000,\"targetTotal\":2000000,\"diffTotal\":0,\"failTotal\":0}";
    private static final String DIFF_DATA =
            "{\"sourceTotal\":100,\"targetTotal\":95,\"diffTotal\":5,\"failTotal\":2}";
    private static final String BROKEN_JSON = "{this-is-not-json";

    @Test
    public void analyze_uniqueRowsAndDerivedMetrics() {
        DatabaseSyncPerfAdviceServiceImpl service = newService(
                task(), meta(1000L, 800L, 50L, 1000L, 3000L), groups(), detailRows());
        DatabaseSyncPerfAdviceVO vo = service.analyze(TASK_ID);

        assertTrue(vo.isMigrated());
        // 唯一口径：totalRows=success+fail，不取 Meta.total
        assertEquals(800L, vo.getSuccessRows());
        assertEquals(50L, vo.getFailRows());
        assertEquals(850L, vo.getTotalRows());
        assertEquals(1000L, vo.getMetaTotal());
        assertFalse(vo.isRowsConsistent());
        assertEquals(2000L, vo.getDurationMs());
        // 800 * 1000 / 2000 = 400.0 行/s（分子只用 successRows）
        assertEquals(400.0D, vo.getThroughputPerSecond(), 0.0001D);
        assertEquals(3, vo.getTableCount());

        // 大表行数取该表明细 sourceTotal
        assertEquals(1, vo.getBigTables().size());
        assertEquals("T_BIG", vo.getBigTables().get(0).getTableName());
        assertEquals(2_000_000L, vo.getBigTables().get(0).getSourceRows());
        // 差异表取明细 diffTotal / failTotal
        assertEquals(2, vo.getDiffTables().size());
        assertEquals(5L, vo.getDiffTables().get(0).getDiffTotal());
        assertEquals(2L, vo.getDiffTables().get(1).getDiffTotal());

        assertNotNull(vo.getConclusion());
        assertFalse(vo.getConclusion().contains("数据总量与差异均为 0"));
        assertTrue(vo.getConclusion().contains("总数据量=成功+失败"));
        assertTrue(vo.getConclusion().contains("请核对运行记录"));
    }

    @Test
    public void analyze_sameTaskTwiceStable() {
        DatabaseSyncPerfAdviceServiceImpl service = newService(
                task(), meta(1000L, 800L, 50L, 1000L, 3000L), groups(), detailRows());
        DatabaseSyncPerfAdviceVO first = service.analyze(TASK_ID);
        DatabaseSyncPerfAdviceVO second = service.analyze(TASK_ID);

        assertEquals(first.getTotalRows(), second.getTotalRows());
        assertEquals(first.getSuccessRows(), second.getSuccessRows());
        assertEquals(first.getFailRows(), second.getFailRows());
        assertEquals(first.getMetaTotal(), second.getMetaTotal());
        assertEquals(first.isRowsConsistent(), second.isRowsConsistent());
        assertEquals(first.getDurationMs(), second.getDurationMs());
        assertEquals(first.getThroughputPerSecond(), second.getThroughputPerSecond(), 0.0001D);
        assertEquals(first.getTableCount(), second.getTableCount());
        assertEquals(first.getBigTables().size(), second.getBigTables().size());
        assertEquals(first.getDiffTables().size(), second.getDiffTables().size());
        assertEquals(first.getConclusion(), second.getConclusion());
    }

    @Test
    public void analyze_consistentMetaNoCheckHint() {
        DatabaseSyncPerfAdviceServiceImpl service = newService(
                task(), meta(850L, 800L, 50L, 1000L, 3000L), groups(), detailRows());
        DatabaseSyncPerfAdviceVO vo = service.analyze(TASK_ID);
        assertTrue(vo.isRowsConsistent());
        assertFalse(vo.getConclusion().contains("请核对运行记录"));
    }

    @Test
    public void analyze_cleanTaskConclusionWording() {
        List<TableGroup> groups = new ArrayList<>();
        groups.add(group("T_BIG"));
        Collection<Map<String, Object>> rows = new ArrayList<>();
        rows.add(detailRow("T_BIG", BIG_DATA));
        DatabaseSyncPerfAdviceServiceImpl service = newService(
                task(), meta(2000000L, 2000000L, 0L, 0L, 1000L), groups, rows);
        DatabaseSyncPerfAdviceVO vo = service.analyze(TASK_ID);
        assertTrue(vo.getConclusion().contains("无失败行且无差异表，一致性良好"));
        assertFalse(vo.getConclusion().contains("数据总量与差异均为 0"));
    }

    @Test
    public void analyze_emptyTaskNoNpe() {
        DatabaseSyncPerfAdviceServiceImpl service = newService(
                task(), meta(0L, 0L, 0L, 0L, 0L),
                new ArrayList<TableGroup>(), new ArrayList<Map<String, Object>>());
        DatabaseSyncPerfAdviceVO vo = service.analyze(TASK_ID);
        assertTrue(vo.isMigrated());
        assertEquals(0L, vo.getTotalRows());
        assertEquals(0L, vo.getSuccessRows());
        assertEquals(0L, vo.getFailRows());
        assertEquals(0L, vo.getDurationMs());
        assertEquals(0.0D, vo.getThroughputPerSecond(), 0.0001D);
        assertEquals(0, vo.getTableCount());
        assertEquals(0, vo.getBigTables().size());
        assertEquals(0, vo.getDiffTables().size());
        assertNotNull(vo.getConclusion());
        assertFalse(vo.getConclusion().contains("数据总量与差异均为 0"));
    }

    @Test
    public void analyze_notMigratedNoNpe() {
        DatabaseSyncPerfAdviceServiceImpl service = newService(
                task(), null, groups(), detailRows());
        DatabaseSyncPerfAdviceVO vo = service.analyze(TASK_ID);
        assertFalse(vo.isMigrated());
        assertEquals(0L, vo.getTotalRows());
        assertEquals(0L, vo.getSuccessRows());
        assertEquals(0L, vo.getFailRows());
        assertEquals(0.0D, vo.getThroughputPerSecond(), 0.0001D);
        assertNotNull(vo.getConclusion());
        assertTrue(vo.getConclusion().contains("尚未执行过迁移"));
    }

    @Test
    public void analyze_brokenDetailJsonNoNpe() {
        List<TableGroup> groups = new ArrayList<>();
        groups.add(group("T_BAD_A"));
        groups.add(group("T_BAD_B"));
        Collection<Map<String, Object>> rows = new ArrayList<>();
        rows.add(detailRow("T_BAD_A", BROKEN_JSON));
        rows.add(detailRow("T_BAD_B", "not-json-at-all"));
        DatabaseSyncPerfAdviceServiceImpl service = newService(
                task(), meta(10L, 10L, 0L, 0L, 100L), groups, rows);
        DatabaseSyncPerfAdviceVO vo = service.analyze(TASK_ID);
        assertEquals(2, vo.getTableCount());
        assertEquals(0, vo.getBigTables().size());
        assertEquals(0, vo.getDiffTables().size());
        assertNotNull(vo.getConclusion());
        assertFalse(vo.getConclusion().contains("数据总量与差异均为 0"));
    }

    // ============ 桩构造 ============

    private DatabaseSyncPerfAdviceServiceImpl newService(final DatabaseSyncTask task,
                                                        final Meta meta,
                                                        final List<TableGroup> groups,
                                                        final Collection<Map<String, Object>> detailRows) {
        DatabaseSyncPerfAdviceServiceImpl service = new DatabaseSyncPerfAdviceServiceImpl();
        inject(service, "taskService", proxy(TaskService.class, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if ("get".equals(m.getName())) {
                    return task;
                }
                return defaultValue(m.getReturnType());
            }
        }));
        inject(service, "metaProfile", proxy(MetaProfile.class, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if ("getMetaByTaskId".equals(m.getName())) {
                    return meta;
                }
                return defaultValue(m.getReturnType());
            }
        }));
        inject(service, "tableGroupProfile", proxy(TableGroupProfile.class, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if ("pageScanTableGroups".equals(m.getName())) {
                    @SuppressWarnings("unchecked")
                    Consumer<List<TableGroup>> consumer = (Consumer<List<TableGroup>>) a[2];
                    consumer.accept(groups);
                    return null;
                }
                return defaultValue(m.getReturnType());
            }
        }));
        inject(service, "taskDetailProfile", proxy(TaskDetailProfile.class, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if ("queryResults".equals(m.getName())) {
                    Paging<Map<String, Object>> paging = new Paging<>(1, 100000);
                    paging.setData(detailRows);
                    return paging;
                }
                return defaultValue(m.getReturnType());
            }
        }));
        return service;
    }

    private static Object proxy(Class<?> iface, InvocationHandler handler) {
        return Proxy.newProxyInstance(
                DatabaseSyncPerfAdviceServiceImplTest.class.getClassLoader(),
                new Class<?>[]{iface}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }

    private static void inject(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("注入桩失败: " + fieldName, e);
        }
    }

    private static DatabaseSyncTask task() {
        DatabaseSyncTask t = new DatabaseSyncTask();
        t.setName("整库迁移-Wave10-T01");
        return t;
    }

    private static Meta meta(long total, long success, long fail, long begin, long end) {
        Meta m = new Meta();
        m.setTotal(new AtomicLong(total));
        m.setSuccess(new AtomicLong(success));
        m.setFail(new AtomicLong(fail));
        m.setBeginTime(begin);
        m.setEndTime(end);
        return m;
    }

    private static List<TableGroup> groups() {
        List<TableGroup> groups = new ArrayList<>();
        groups.add(group("T_BIG"));
        groups.add(group("T_DIFF"));
        groups.add(group("T_BAD"));
        return groups;
    }

    private static TableGroup group(String targetTableName) {
        Table table = new Table();
        table.setName(targetTableName);
        TableGroup g = new TableGroup();
        g.setTargetTable(table);
        return g;
    }

    private static Collection<Map<String, Object>> detailRows() {
        Collection<Map<String, Object>> rows = new ArrayList<>();
        rows.add(detailRow("T_BIG", BIG_DATA));
        rows.add(detailRow("T_DIFF", DIFF_DATA));
        rows.add(detailRow("T_BAD", BROKEN_JSON));
        return rows;
    }

    private static Map<String, Object> detailRow(String targetTable, String dataJson) {
        Map<String, Object> row = new HashMap<>();
        row.put("targetTable", targetTable);
        row.put("data", dataJson);
        return row;
    }
}
