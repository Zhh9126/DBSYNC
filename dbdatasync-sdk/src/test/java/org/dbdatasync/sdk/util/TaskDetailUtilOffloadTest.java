package org.dbdatasync.sdk.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.storage.TaskDetailContentStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link TaskDetailUtil} 超大内容外置接入单元测试：
 * 未安装 store 时保持既有截断行为；安装后超限内容整体外置并可在读取端反解。
 */
public class TaskDetailUtilOffloadTest {

    /** 简易内存 store：仅支持单引用存储，用于验证 SPI 接线。 */
    private static final class MemoryTaskDetailStore implements TaskDetailContentStore {
        String stored;

        @Override
        public String store(String content) {
            this.stored = content;
            return REF_PREFIX + "mem-" + content.length();
        }

        @Override
        public String resolve(String stored) {
            if (stored == null || !stored.startsWith(REF_PREFIX)) {
                return stored;
            }
            return this.stored;
        }
    }

    @Before
    public void setUp() {
        TaskDetailContentStore.uninstall();
    }

    @After
    public void tearDown() {
        TaskDetailContentStore.uninstall();
    }

    /**
     * 未安装 store：超限内容仍走既有截断路径（内容字段被清空，JSON 仍可解析）。
     */
    @Test
    public void withoutStoreKeepsTruncationBehavior() {
        Map<String, Object> content = new HashMap<String, Object>();
        content.put("id", "abc");
        content.put(ConfigConstant.TASK_CONTENT, bigContent());
        byte[] bytes = TaskDetailUtil.serializeContent(content);
        assertTrue("截断后仍应小于上限", bytes.length <= TaskDetailUtil.MAX_DATA_BYTES);
        // 反序列化应得到可解析 Map（content 被截断/清空）
        Map<String, Object> parsed = TaskDetailUtil.deserializeContent(bytes);
        assertEquals("abc", parsed.get("id"));
    }

    /**
     * 安装 store 后：超限内容整体外置为引用串，DATA 列存引用串（远小于上限），
     * 读取端反解还原完整内容。
     */
    @Test
    public void withStoreOffloadsOversizedContent() {
        TaskDetailContentStore.install(new MemoryTaskDetailStore());
        Map<String, Object> content = new HashMap<String, Object>();
        content.put("id", "abc");
        content.put(ConfigConstant.TASK_CONTENT, bigContent());
        byte[] bytes = TaskDetailUtil.serializeContent(content);
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue("应存引用串", json.startsWith(TaskDetailContentStore.REF_PREFIX));
        // 引用串本身必须远小于上限
        assertTrue(json.length() < 100);
        Map<String, Object> parsed = TaskDetailUtil.deserializeContent(bytes);
        assertEquals("abc", parsed.get("id"));
        assertEquals(bigContent(), parsed.get(ConfigConstant.TASK_CONTENT));
    }

    /**
     * 未超限时不外置（阈值内原样返回，不触发 store）。
     */
    @Test
    public void underThresholdNotOffloaded() {
        TaskDetailContentStore.install(new MemoryTaskDetailStore());
        Map<String, Object> content = new HashMap<String, Object>();
        content.put("id", "abc");
        content.put("small", "hello");
        byte[] bytes = TaskDetailUtil.serializeContent(content);
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue("不应为引用串", !json.startsWith(TaskDetailContentStore.REF_PREFIX));
        Map<String, Object> parsed = TaskDetailUtil.deserializeContent(bytes);
        assertEquals("abc", parsed.get("id"));
        assertEquals("hello", parsed.get("small"));
    }

    @Test
    public void nullOrEmptyContentReturnsNull() {
        assertNull(TaskDetailUtil.serializeContent(null));
        assertNull(TaskDetailUtil.serializeContent(new HashMap<String, Object>()));
    }

    private static String bigContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30000; i++) {
            sb.append('中');
        }
        return sb.toString();
    }
}
