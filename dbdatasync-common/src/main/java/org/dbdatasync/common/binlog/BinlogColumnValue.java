/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.common.binlog;

import com.google.protobuf.ByteString;
import org.dbdatasync.common.column.AbstractColumnValue;
import org.dbdatasync.common.enums.BinlogByteEnum;
import org.dbdatasync.common.util.NumberUtil;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2022-06-30 22:39
 */
public class BinlogColumnValue extends AbstractColumnValue<ByteString> {

    public BinlogColumnValue(ByteString v) {
        setValue(v);
    }

    @Override
    public String asString() {
        return getValue().toStringUtf8();
    }

    @Override
    public byte[] asByteArray() {
        return getValue().toByteArray();
    }

    @Override
    public Byte asByte() {
        return getValue().byteAt(0);
    }

    @Override
    public Short asShort() {
        byte[] bytes = asByteArray();
        int expectedLength = BinlogByteEnum.SHORT.getByteLength();

        // 如果字节数组长度小于期望长度，进行填充
        if (bytes.length < expectedLength) {
            byte[] paddedBytes = new byte[expectedLength];
            System.arraycopy(bytes, 0, paddedBytes, 0, bytes.length);
            // 剩余字节填充为0
            ByteBuffer buffer = ByteBuffer.allocate(expectedLength);
            buffer.put(paddedBytes);
            buffer.flip();
            return buffer.asShortBuffer().get();
        }

        // 如果字节数组长度大于等于期望长度，只取前 expectedLength 字节
        ByteBuffer buffer = ByteBuffer.allocate(expectedLength);
        buffer.put(bytes, 0, expectedLength);
        buffer.flip();
        return buffer.asShortBuffer().get();
    }

    @Override
    public Integer asInteger() {
        byte[] bytes = asByteArray();
        if (bytes.length == BinlogByteEnum.BYTE.getByteLength()) {
            return NumberUtil.toInt(asString());
        }
        if (bytes.length == BinlogByteEnum.SHORT.getByteLength()) {
            Short aShort = asShort();
            return new Integer(aShort);
        }
        // 长度不足时补零(如 MEDIUMINT/INT24 为 3 字节), 超长时截断, 避免 BufferOverflow
        return (int) readPadded(bytes, BinlogByteEnum.INTEGER.getByteLength()).asIntBuffer().get();
    }

    @Override
    public Long asLong() {
        return readPadded(asByteArray(), BinlogByteEnum.LONG.getByteLength()).asLongBuffer().get();
    }

    @Override
    public Float asFloat() {
        byte[] bytes = asByteArray();
        int expectedLength = BinlogByteEnum.FLOAT.getByteLength();

        // 如果字节数组长度小于期望长度，进行填充
        if (bytes.length < expectedLength) {
            byte[] paddedBytes = new byte[expectedLength];
            System.arraycopy(bytes, 0, paddedBytes, 0, bytes.length);
            // 剩余字节填充为0
            ByteBuffer buffer = ByteBuffer.allocate(expectedLength);
            buffer.put(paddedBytes);
            buffer.flip();
            return buffer.asFloatBuffer().get();
        }

        // 如果字节数组长度大于等于期望长度，只取前 expectedLength 字节
        ByteBuffer buffer = ByteBuffer.allocate(expectedLength);
        buffer.put(bytes, 0, expectedLength);
        buffer.flip();
        return buffer.asFloatBuffer().get();
    }

    @Override
    public Double asDouble() {
        byte[] bytes = asByteArray();
        int expectedLength = BinlogByteEnum.DOUBLE.getByteLength();

        // 如果字节数组长度小于期望长度，进行填充
        if (bytes.length < expectedLength) {
            byte[] paddedBytes = new byte[expectedLength];
            System.arraycopy(bytes, 0, paddedBytes, 0, bytes.length);
            // 剩余字节填充为0
            ByteBuffer buffer = ByteBuffer.allocate(expectedLength);
            buffer.put(paddedBytes);
            buffer.flip();
            return buffer.asDoubleBuffer().get();
        }

        // 如果字节数组长度大于等于期望长度，只取前 expectedLength 字节
        ByteBuffer buffer = ByteBuffer.allocate(expectedLength);
        buffer.put(bytes, 0, expectedLength);
        buffer.flip();
        return buffer.asDoubleBuffer().get();
    }

    @Override
    public Boolean asBoolean() {
        return readPadded(asByteArray(), BinlogByteEnum.SHORT.getByteLength()).asShortBuffer().get() == 1;
    }

    /**
     * 按期望长度读取: 长度不足补零, 超长截断, 保证任意长度输入均可安全读取。
     */
    private static ByteBuffer readPadded(byte[] bytes, int expectedLength) {
        ByteBuffer buffer = ByteBuffer.allocate(expectedLength);
        int n = Math.min(bytes.length, expectedLength);
        buffer.put(bytes, 0, n);
        // 不足部分补零, 使 position==capacity, 保证 asXxxBuffer().get() 可读
        for (int i = n; i < expectedLength; i++) {
            buffer.put((byte) 0);
        }
        buffer.flip();
        return buffer;
    }

    @Override
    public BigDecimal asBigDecimal() {
        return new BigDecimal(asString());
    }

    @Override
    public Date asDate() {
        return new Date(asLong());
    }

    @Override
    public Timestamp asTimestamp() {
        return new Timestamp(asLong());
    }

    @Override
    public Time asTime() {
        return new Time(asLong());
    }
}
