package org.dbdatasync.biz.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.dbdatasync.sdk.storage.TaskDetailContentStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link OversizedContentTaskDetailStore} 单元测试：验证 SPI 安装/卸载、
 * store/resolve 委托 {@link OversizedContentService}、引用串前缀一致。
 */
public class OversizedContentTaskDetailStoreTest {

    private OversizedContentService service;

    @Before
    public void setUp() {
        TaskDetailContentStore.uninstall();
        service = new OversizedContentService();
    }

    @After
    public void tearDown() {
        TaskDetailContentStore.uninstall();
    }

    /**
     * 默认小内容内联，无需外置：SPI 未安装时 TaskDetailUtil 保持既有行为
     * （本用例验证 service 自身语义，由 TaskDetailUtil 测试覆盖 SPI 接入）。
     */
    @Test
    public void smallContentInlined() {
        String content = "{\"a\":1}";
        assertEquals(content, service.store(content));
        assertEquals(content, service.resolve(content));
    }

    @Test
    public void installAndUninstallLifecycle() {
        OversizedContentTaskDetailStore bridge = new OversizedContentTaskDetailStore(service);
        assertNull(TaskDetailContentStore.current());
        bridge.init();
        assertSame(bridge, TaskDetailContentStore.current());
        bridge.destroy();
        assertNull(TaskDetailContentStore.current());
    }

    @Test
    public void oversizedContentOffloadedAndResolvable() {
        OversizedContentTaskDetailStore bridge = new OversizedContentTaskDetailStore(service);
        bridge.init();
        try {
            // 3 万中文字符 ≈ 9 万字节 > 65535 → 分块外置
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30000; i++) {
                sb.append('中');
            }
            String big = sb.toString();
            String stored = bridge.store(big);
            assertTrue("应返回引用串", stored.startsWith(TaskDetailContentStore.REF_PREFIX));
            String resolved = bridge.resolve(stored);
            assertEquals(big, resolved);
        } finally {
            bridge.destroy();
        }
    }

    @Test
    public void refPrefixAlignedWithSdk() {
        assertEquals(OversizedContentOffloader.REF_PREFIX, TaskDetailContentStore.REF_PREFIX);
    }

    /**
     * 反射读取内联阈值，构造超限内容时用真实阈值，避免依赖默认值。
     */
    @Test
    public void contentBelowThresholdReservedInline() throws Exception {
        Field field = OversizedContentService.class.getDeclaredField("maxInlineBytes");
        field.setAccessible(true);
        int threshold = field.getInt(service);
        String small = new String(new char[threshold / 3]).replace('\0', 'a');
        OversizedContentTaskDetailStore bridge = new OversizedContentTaskDetailStore(service);
        bridge.init();
        try {
            String stored = bridge.store(small);
            assertEquals(small, stored);
        } finally {
            bridge.destroy();
        }
    }
}
