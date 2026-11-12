package org.dbdatasync.biz.slowsql;

import java.util.List;

/**
 * 慢 SQL 采集器接口：采集与快照读取。
 *
 * <p>实现类需保证线程安全；{@link #snapshot()} 必须返回
 * 内部数据的只读副本，外部修改不得影响采集器内部状态。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public interface SlowSqlCollector {

    /**
     * 采集一条慢 SQL 记录。
     *
     * @param record 待采集记录
     */
    void collect(SlowSqlRecord record);

    /**
     * 当前采集快照（只读副本）。
     *
     * @return 记录列表副本，调用方可安全修改
     */
    List<SlowSqlRecord> snapshot();
}
