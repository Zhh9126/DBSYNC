/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.mysql.deserializer;

import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * 跨平台 binlog 字符串/无符号整数反序列化基元。
 *
 * <p>上游 mysql-binlog-connector 的 readString 使用 new String(byte[])（平台默认字符集），
 * 在中文 Windows（GBK）上会把 binlog 中的 UTF-8 中文/emoji 解成乱码；
 * deserializeLongLong 返回有符号 Long，BIGINT UNSIGNED 越界翻负。
 * 本基元统一修复：字符串显式 UTF-8，无符号 8 字节整数升级 BigInteger。</p>
 */
public final class CrossPlatformDeserializers {

    private CrossPlatformDeserializers() {
    }

    /**
     * CHAR(n) / BINARY(n): 长度前缀 1 字节(长度<256) 或 2 字节。
     */
    public static Serializable deserializeString(int length, ByteArrayInputStream inputStream) throws IOException {
        int dataLength = length < 256 ? inputStream.readInteger(1) : inputStream.readInteger(2);
        return new String(inputStream.read(dataLength), StandardCharsets.UTF_8);
    }

    /**
     * VARCHAR(n) / VARBINARY(n): 同上长度前缀规则。
     */
    public static Serializable deserializeVarString(int length, ByteArrayInputStream inputStream) throws IOException {
        int dataLength = length < 256 ? inputStream.readInteger(1) : inputStream.readInteger(2);
        return new String(inputStream.read(dataLength), StandardCharsets.UTF_8);
    }

    /**
     * BIGINT UNSIGNED: 8 字节小端整数, 超过 Long.MAX_VALUE 时以 BigInteger 返回(MySQL JDBC 同样以 BigInteger 承载)。
     */
    public static Serializable deserializeLongLong(ByteArrayInputStream inputStream) throws IOException {
        byte[] bytes = inputStream.read(8);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            // binlog 整数为小端
            value |= (bytes[i] & 0xFFL) << (8 * i);
        }
        if (value >= 0) {
            return value;
        }
        return toUnsignedBigInteger(bytes);
    }

    private static BigInteger toUnsignedBigInteger(byte[] littleEndian) {
        byte[] bigEndian = new byte[8];
        for (int i = 0; i < 8; i++) {
            bigEndian[i] = littleEndian[7 - i];
        }
        return new BigInteger(1, bigEndian);
    }
}
