package org.dbdatasync.connector.postgresql;

import org.dbdatasync.sdk.model.Field;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * PostgreSQL/Kingbase 跨方言类型落地校验（离线、零 Spring、纯静态）。
 *
 * <p>覆盖：Oracle/MySQL 源端二进制类型在 PG/Kingbase 目标端应落为 BYTEA（PG 无 BLOB/BINARY 类型），
 * 以及 Oracle 常见类型经基类归一后在 PG 落地的等价名。KingbaseConnector 继承本类，故一并覆盖。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class PostgreSQLConnectorTypeMappingTest {

    private final PostgreSQLConnector connector = new PostgreSQLConnector();

    private Field field(String typeName) {
        Field f = new Field();
        f.setTypeName(typeName);
        // 模拟 Oracle NUMBER(p,s) 的 JDBC type，触发基类 DECIMAL 归一
        return f;
    }

    @Test
    public void oracleBlobBecomesBytea() {
        assertEquals("BYTEA", connector.formatPhysicalType(field("BLOB")));
    }

    @Test
    public void mysqlLongBlobBecomesBytea() {
        assertEquals("BYTEA", connector.formatPhysicalType(field("LONGBLOB")));
    }

    @Test
    public void mysqlMediumBlobBecomesBytea() {
        assertEquals("BYTEA", connector.formatPhysicalType(field("MEDIUMBLOB")));
    }

    @Test
    public void oracleRawBecomesBytea() {
        // 基类会把 RAW 归一为 VARBINARY，这里直接校验 VARBINARY 也落 BYTEA
        assertEquals("BYTEA", connector.formatPhysicalType(field("VARBINARY")));
    }

    @Test
    public void oracleVarchar2BecomesVarchar() {
        // 基类对无长度的 CHAR 族补 (255)，PG 接受
        assertEquals("VARCHAR(255)", connector.formatPhysicalType(field("VARCHAR2")));
    }

    @Test
    public void oracleNumberBecomesDecimal() {
        Field f = field("NUMBER");
        f.setType(2); // java.sql.Types.NUMERIC
        f.setColumnSize(10);
        f.setRatio(2);
        assertEquals("DECIMAL(10,2)", connector.formatPhysicalType(f));
    }

    @Test
    public void pgNativeSerialToInteger() {
        assertEquals("INT4", connector.formatPhysicalType(field("SERIAL")));
    }

    @Test
    public void pgNativeByteaPassThrough() {
        assertEquals("BYTEA", connector.formatPhysicalType(field("BYTEA")));
    }
}
