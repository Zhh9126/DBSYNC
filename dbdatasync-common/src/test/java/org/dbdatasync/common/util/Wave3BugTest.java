package org.dbdatasync.common.util;

import com.google.protobuf.ByteString;
import org.dbdatasync.common.binlog.BinlogColumnValue;
import org.dbdatasync.common.util.DataChunkUtil.DataChunk;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Wave3 深度BUG测试：只读SQL绕过 / 分片多字节损坏 / binlog 短字节溢出。
 * 断言修复后的期望行为（修复前运行应失败，即为BUG复现）。
 */
public class Wave3BugTest {

    // ==================== 1. 只读SQL校验绕过 ====================

    @Test
    public void readonlySql_cteDelete_shouldBeRejected() {
        try {
            SqlIdentifierValidator.validateReadonlySql(
                "WITH d AS (DELETE FROM my_user RETURNING *) SELECT * FROM d");
            fail("CTE写语句绕过了只读校验(高危绕过)");
        } catch (IllegalArgumentException expected) {
            // 期望:拒绝
        }
    }

    @Test
    public void readonlySql_cteUpdate_shouldBeRejected() {
        try {
            SqlIdentifierValidator.validateReadonlySql(
                "WITH u AS (UPDATE t SET a = 1 RETURNING *) SELECT * FROM u");
            fail("CTE UPDATE绕过了只读校验");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void readonlySql_selectIntoOutfile_shouldBeRejected() {
        try {
            SqlIdentifierValidator.validateReadonlySql(
                "SELECT * FROM my_user INTO OUTFILE '/tmp/x'");
            fail("INTO OUTFILE绕过了只读校验");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void readonlySql_normalSelect_passes() {
        String sql = SqlIdentifierValidator.validateReadonlySql(
            "select id, name from MY_USER where id > 100");
        assertEquals("select id, name from MY_USER where id > 100", sql);
    }

    @Test
    public void readonlySql_normalWithSelect_passes() {
        String sql = SqlIdentifierValidator.validateReadonlySql(
            "WITH t AS (SELECT id FROM my_user) SELECT * FROM t");
        assertNotNull(sql);
    }

    // ==================== 2. 分片多字节字符损坏 ====================

    @Test
    public void chunk_utf8Chinese_roundtrip_allBoundaries() {
        // 构造 >100KB 含中文/emoji 的内容, 用 >100KB 的分片尺寸触发多字节边界切割
        String unit = "中文数据同步测试\uD83D\uDE00abc123,";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 100 * 1024) {
            sb.append(unit);
        }
        String data = sb.toString();
        for (int chunkBytes : new int[]{101 * 1024, 103 * 1024, 107 * 1024}) {
            List<DataChunk> chunks = DataChunkUtil.chunkData(data, chunkBytes);
            assertTrue("应产生多个分片", chunks.size() > 1);
            String merged = DataChunkUtil.mergeChunks(chunks);
            assertEquals("分片大小" + chunkBytes + "字节时中文内容损坏", data, merged);
        }
    }

    @Test
    public void chunk_pureMultibyte_roundtrip() {
        // 全中文, 任意字节边界必然落在多字节中间
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40 * 1024; i++) {
            sb.append('汉');
        }
        String data = sb.toString(); // 120KB UTF-8
        List<DataChunk> chunks = DataChunkUtil.chunkData(data, 0);
        String merged = DataChunkUtil.mergeChunks(chunks);
        assertEquals(data, merged);
    }

    // ==================== 3. binlog 短字节数组溢出 ====================

    @Test
    public void binlogValue_mediumInt3Bytes_asInteger() {
        // MySQL MEDIUMINT(INT24) 在 binlog 中为 3 字节, 不足 Integer 4 字节
        BinlogColumnValue v = new BinlogColumnValue(ByteString.copyFrom(new byte[]{1, 2, 3}));
        assertNotNull(v.asInteger());
    }

    @Test
    public void binlogValue_shortBytes_asLong() {
        BinlogColumnValue v = new BinlogColumnValue(
            ByteString.copyFrom(new byte[]{1, 2, 3, 4, 5}));
        assertNotNull(v.asLong());
    }

    @Test
    public void binlogValue_singleByte_asBoolean() {
        BinlogColumnValue v = new BinlogColumnValue(ByteString.copyFrom(new byte[]{1}));
        assertNotNull(v.asBoolean());
    }
}
