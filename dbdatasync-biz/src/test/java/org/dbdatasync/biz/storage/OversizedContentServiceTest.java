package org.dbdatasync.biz.storage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link OversizedContentService} 运行时接线层单测（纯逻辑，无 Spring 上下文）。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class OversizedContentServiceTest {

    @Test
    public void inlinePassthroughBelowThreshold() {
        OversizedContentService service = new OversizedContentService();
        String small = "普通内容";
        String stored = service.store(small);
        assertEquals(small, stored);
        assertFalse(service.isRef(stored));
        assertEquals(small, service.resolve(stored));
    }

    @Test
    public void oversizedContentOffloadedAndResolved() {
        OversizedContentService service = new OversizedContentService();
        service.configure(1024);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("中文内容😀混排");
        }
        String content = sb.toString();
        assertTrue(OversizedContentOffloader.utf8Length(content) > 1024);

        String stored = service.store(content);
        assertTrue("超限内容应外置为引用串", service.isRef(stored));
        assertEquals(content, service.resolve(stored));

        service.delete(stored);
        try {
            service.resolve(stored);
            throw new AssertionError("删除后应抛出 IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void thresholdConfigureAndBoundary() {
        OversizedContentService service = new OversizedContentService();
        service.configure(10);
        assertEquals(10, service.getMaxInlineBytes());

        // UTF-8 字节恰好在阈值内（"中"=3 字节）
        String exactly = "中中中中"; // 12 字节 > 10 → 外置
        String stored = service.store(exactly);
        assertTrue(service.isRef(stored));

        String fits = "中中中"; // 9 字节 <= 10 → 内联
        String storedFits = service.store(fits);
        assertEquals(fits, storedFits);

        // 非法配置回退默认
        service.configure(-1);
        assertEquals(OversizedContentOffloader.INLINE_THRESHOLD_DEFAULT, service.getMaxInlineBytes());
    }
}
