/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.kingbase.cdc;

import com.kingbase8.replication.fluent.logical.ChainedLogicalStreamBuilder;
import org.dbdatasync.connector.postgresql.decoder.impl.PgOutputMessageDecoder;

/**
 * 金仓 KES V9 唯一内置的逻辑解码输出插件：syslogical_output（pgoutput 二进制协议风格）。
 * <p>
 * 兼容性结论（实测 KingbaseES V009R003C011）：
 * <ul>
 *  <li>KES V9 不自带 test_decoding / pgoutput / wal2json 插件，仅提供 syslogical_output；</li>
 *  <li>其流选项遵循 pgoutput 协议：必须 proto_version &gt;= 1 且传 publication_names；</li>
 *  <li>test_decoding 的 include-xids / skip-empty-xacts 选项会报 unrecognized option。</li>
 * </ul>
 * 因此该插件复用 {@link PgOutputMessageDecoder} 的消息解码逻辑，仅替换输出插件名，
 * 并提供金仓驱动 builder 的流选项注入方法（金仓驱动与 PG 驱动的 builder 类型不同，不能直接复用接口方法）。
 *
 * @author 穿云
 * @version 1.0.0
 * @date 2026-10-18
 */
public class KingbaseSyslogicalOutputDecoder extends PgOutputMessageDecoder {

    @Override
    public String getOutputPlugin() {
        return "syslogical_output";
    }

    /**
     * 金仓驱动的流选项：pgoutput 协议风格（proto_version + publication_names）。
     */
    public ChainedLogicalStreamBuilder configureStream(ChainedLogicalStreamBuilder builder) {
        builder.withSlotOption("proto_version", 1);
        builder.withSlotOption("publication_names", getPubName());
        return builder;
    }
}
