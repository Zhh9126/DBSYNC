package org.dbdatasync.connector.oracle.schema;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Wave30：Oracle BLOB 字节流必须循环读满。
 * 旧实现只调用一次 {@code is.read(byte[])}，驱动分块返回时后半段全是 0，属于静默数据损坏。
 */
public class Wave30OracleLobReadTest {

    /** 模拟驱动按 3 字节分块返回的 BLOB 流。 */
    private static final class ChunkedStream extends ByteArrayInputStream {
        ChunkedStream(byte[] data) {
            super(data);
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            return super.read(b, off, Math.min(3, len));
        }
    }

    @Test
    public void readsFullBlobAcrossChunks() throws Exception {
        byte[] data = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        InputStream in = new ChunkedStream(data);
        byte[] actual = OracleSchemaResolver.readFully(in, data.length);
        assertArrayEquals(data, actual);
    }

    @Test
    public void shortStreamReturnsActualBytes() throws Exception {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] actual = OracleSchemaResolver.readFully(new ChunkedStream(data), 10L);
        assertEquals("abc", new String(actual, StandardCharsets.UTF_8));
    }

    @Test
    public void oversizedLobIsRejectedInsteadOfOverflowing() {
        try {
            OracleSchemaResolver.readFully(new ByteArrayInputStream(new byte[0]),
                    (long) Integer.MAX_VALUE + 1L);
            fail("超过 2GB 的 LOB 必须显式拒绝，不能 (int) 强转溢出");
        } catch (IOException expected) {
            assertEquals(true, expected.getMessage().contains("exceeds 2GB"));
        }
    }
}
