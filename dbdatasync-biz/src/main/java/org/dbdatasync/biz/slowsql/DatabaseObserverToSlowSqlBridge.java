package org.dbdatasync.biz.slowsql;

import org.dbdatasync.sdk.observer.DatabaseExecuteObserver;
import org.dbdatasync.sdk.observer.DatabaseExecuteObservers;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * 数据库执行观察 → 慢 SQL 采集桥接：把引擎（{@code DatabaseTemplate}）
 * 的 SQL 执行观察点接入 {@link SlowSqlService} 采集，实现引擎主链路
 * 慢 SQL 自动插桩，连接器与引擎代码零改造。
 *
 * <p>接线方式：本组件是 Spring 单例（web 扫 {@code org.dbdatasync} 全包），
 * 初始化时向 {@link DatabaseExecuteObservers} 注册；观察点由 sdk 层
 * {@code DatabaseTemplate} 在每个 SQL 执行成功后回调。回调方自带阈值判断
 * （<code>executeMillis &gt;= thresholdMillis</code> 才采集），避免高频旁路通知。
 * 卸载时反注册，测试隔离无残留。</p>
 *
 * <p>来源标识：观察事件携带的连接驱动类名（SimpleConnection 等），
 * bridge 前拼 {@code observer:<类名>} 前缀便于在慢 SQL 页区分来源。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
@Component
public class DatabaseObserverToSlowSqlBridge implements DatabaseExecuteObserver {

    private final SlowSqlService slowSqlService;

    /**
     * 构造桥接实现。
     *
     * @param slowSqlService 慢 SQL 采集服务（不可为 null）
     */
    public DatabaseObserverToSlowSqlBridge(SlowSqlService slowSqlService) {
        if (slowSqlService == null) {
            throw new IllegalArgumentException("slowSqlService must not be null");
        }
        this.slowSqlService = slowSqlService;
    }

    @PostConstruct
    public void init() {
        DatabaseExecuteObservers.register(this);
    }

    @PreDestroy
    public void destroy() {
        DatabaseExecuteObservers.unregister(this);
    }

    @Override
    public void onExecuteFinished(String sql, String source, long executeMillis, long rows) {
        // 阈值过滤：未超阈值的执行不入采集（与慢 SQL 页口径一致）
        long threshold = slowSqlService.getThreshold().getThresholdMillis();
        if (executeMillis < threshold) {
            return;
        }
        String sourceLabel = source != null ? "observer:" + source : "observer:unknown";
        slowSqlService.collect(sql, sourceLabel, executeMillis, rows);
    }
}
