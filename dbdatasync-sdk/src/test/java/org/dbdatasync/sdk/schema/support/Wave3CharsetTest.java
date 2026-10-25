package org.dbdatasync.sdk.schema.support;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Wave3 跨平台字符集测试：byte[] 转换必须显式 UTF-8，
 * 不得依赖 JVM 默认字符集(中文Windows=GBK / Linux=UTF-8, 行为不一致)。
 *
 * <p>在 -Dfile.encoding=GBK 下运行本测试即可复现修复前的中文乱码。</p>
 */
public class Wave3CharsetTest {

    private static final String CHINESE = "中文同步测试数据";

    @Test
    public void stringType_byteArray_usesUtf8RegardlessOfPlatformCharset() {
        StringType type = new StringType() {
            @Override
            protected String merge(Object val, org.dbdatasync.sdk.model.Field field) {
                return String.valueOf(val);
            }

            @Override
            public java.util.Set<String> getSupportedTypeName() {
                return java.util.Collections.singleton("string");
            }
        };
        // 模拟源库 UTF-8 中文(强制以 UTF-8 编码), 平台默认字符集可能是 GBK
        byte[] utf8Bytes = CHINESE.getBytes(StandardCharsets.UTF_8);
        Object converted = type.convert(utf8Bytes, null);
        assertEquals("平台默认字符集非UTF-8时中文损坏(file.encoding=" + Charset.defaultCharset() + ")",
                CHINESE, converted);
    }

    @Test
    public void timestampType_byteArray_usesUtf8() {
        TimestampType type = new TimestampType() {
            @Override
            protected java.sql.Timestamp merge(Object val, org.dbdatasync.sdk.model.Field field) {
                return (java.sql.Timestamp) val;
            }

            @Override
            public java.util.Set<String> getSupportedTypeName() {
                return java.util.Collections.singleton("timestamp");
            }
        };
        String ts = "2026-10-25 12:00:00";
        Object converted = type.convert(ts.getBytes(StandardCharsets.UTF_8), null);
        assertNotNull("时间戳字节数组转换失败", converted);
        assertEquals(ts, converted.toString().substring(0, 19));
    }
}
