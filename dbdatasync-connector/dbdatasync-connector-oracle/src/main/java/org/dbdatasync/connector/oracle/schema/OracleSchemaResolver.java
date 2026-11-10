/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.connector.oracle.schema;

import com.google.protobuf.ByteString;
import oracle.sql.BLOB;
import oracle.sql.CLOB;
import oracle.sql.STRUCT;
import oracle.sql.TIMESTAMP;
import org.apache.commons.io.IOUtils;
import org.dbdatasync.common.enums.BinlogByteEnum;
import org.dbdatasync.connector.oracle.OracleException;
import org.dbdatasync.connector.oracle.schema.support.OracleBytesType;
import org.dbdatasync.connector.oracle.schema.support.OracleDateType;
import org.dbdatasync.connector.oracle.schema.support.OracleDecimalType;
import org.dbdatasync.connector.oracle.schema.support.OracleDoubleType;
import org.dbdatasync.connector.oracle.schema.support.OracleFloatType;
import org.dbdatasync.connector.oracle.schema.support.OracleIntType;
import org.dbdatasync.connector.oracle.schema.support.OracleLongType;
import org.dbdatasync.connector.oracle.schema.support.OracleStringType;
import org.dbdatasync.connector.oracle.schema.support.OracleTimestampType;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.schema.AbstractDatabaseSchemaResolver;
import org.dbdatasync.sdk.schema.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Stream;

/**
 * @author 穿云
 * @version 1.0.0
 * @date 2024-12-24 23:45
 */
public class OracleSchemaResolver extends AbstractDatabaseSchemaResolver {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    protected void initDataTypeMapping(Map<String, DataType> mapping) {
        Stream.of(
            new OracleBytesType(),
            new OracleDateType(),
            new OracleDecimalType(),
            new OracleDoubleType(),
            new OracleFloatType(),
            new OracleIntType(),
            new OracleLongType(),
            new OracleStringType(),
            new OracleTimestampType())
        .forEach(t->t.getSupportedTypeName().forEach(typeName-> {
            if (mapping.containsKey(typeName)) {
                throw new OracleException("Duplicate type name: " + typeName);
            }
            mapping.put(typeName, t);
        }));
    }

    @Override
    public ByteString serialize(Object value, Field field) {
        if (value == null) {
            return null;
        }
        // MERGE 写入会把 CLOB/TEXT 包成 OracleLobParameter、BLOB 包成 OracleBlobParameter；明细落库只需原文
        if (value instanceof OracleLobParameter) {
            return ByteString.copyFromUtf8(value.toString());
        }
        if (value instanceof OracleBlobParameter) {
            byte[] bytes = ((OracleBlobParameter) value).getValue();
            return bytes == null ? null : ByteString.copyFrom(bytes);
        }
        if (value instanceof ByteString) {
            return (ByteString) value;
        }
        String type = value.getClass().getName();
        switch (type) {
            case "oracle.sql.TIMESTAMP":
                return allocateByteBufferToByteString(BinlogByteEnum.LONG, buffer-> {
                    TIMESTAMP timeStamp = (TIMESTAMP) value;
                    try {
                        buffer.putLong(timeStamp.timestampValue().getTime());
                    } catch (SQLException e) {
                        logger.error(e.getMessage());
                    }
                });
            case "oracle.sql.BLOB":
                return ByteString.copyFrom(getBytes((BLOB) value));
            case "oracle.sql.CLOB":
                return ByteString.copyFrom(getBytes((CLOB) value));
            case "oracle.sql.STRUCT":
                return ByteString.copyFrom(getBytes((STRUCT) value));
            default:
                break;
        }
        return super.serialize(value, field);
    }

    /**
     * Wave30：完整读取 LOB 字节流。
     * <p>{@code InputStream.read(byte[])} 不保证一次读满（Oracle 驱动常按 8K/32K 分块返回），
     * 旧实现只读一次就把剩余部分留成 0，属于静默数据损坏；(int) length 对 &gt;2GB LOB 还会溢出为负。</p>
     * 包级可见以便离线单测。
     */
    static byte[] readFully(InputStream is, long length) throws java.io.IOException {
        if (length < 0) {
            throw new java.io.IOException("negative LOB length: " + length);
        }
        if (length > Integer.MAX_VALUE) {
            throw new java.io.IOException("LOB exceeds 2GB, cannot materialize: " + length);
        }
        byte[] b = new byte[(int) length];
        int off = 0;
        while (off < b.length) {
            int n = is.read(b, off, b.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return off == b.length ? b : java.util.Arrays.copyOf(b, off);
    }

    private byte[] getBytes(BLOB blob) {
        InputStream is = null;
        try {
            is = blob.getBinaryStream();
            return readFully(is, blob.length());
        } catch (Exception e) {
            logger.error("读取 Oracle BLOB 失败: {}", e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(is);
        }
        return new byte[0];
    }

    private byte[] getBytes(CLOB clob) {
        java.io.Reader reader = null;
        try {
            long length = clob.length();
            if (length <= 0) {
                return new byte[0];
            }
            if (length > Integer.MAX_VALUE) {
                throw new java.io.IOException("CLOB exceeds 2GB, cannot materialize: " + length);
            }
            reader = clob.getCharacterStream();
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder((int) length);
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString().getBytes(Charset.defaultCharset());
        } catch (Exception e) {
            logger.error("读取 Oracle CLOB 失败: {}", e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(reader);
        }
        return new byte[0];
    }

    private byte[] getBytes(STRUCT v) {
        try {
            return v.toBytes();
        } catch (SQLException e) {
            logger.error(e.getMessage());
            throw new OracleException(e);
        }
    }
}
