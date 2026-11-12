/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.observer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 数据库执行观察者静态注册点（SPI 容器）。
 *
 * <p>SDK 层只定义接口与本注册点，不持有任何实现；上层（biz/web）在
 * Spring 容器初始化完成后调用 {@link #register(DatabaseExecuteObserver)} 注入。
 * 默认无实现时 {@link #notifyExecuted} 直接空返回，主链路零开销。</p>
 *
 * <p>线程安全：{@link CopyOnWriteArrayList}，注册/通知均线程安全；观察者
 * 异常被吞噬并记录日志，不向主链路抛错。为防观察者误改状态，通知顺序为注册顺序。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public final class DatabaseExecuteObservers {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseExecuteObservers.class);

    private static final CopyOnWriteArrayList<DatabaseExecuteObserver> OBSERVERS = new CopyOnWriteArrayList<>();

    /** 工具类，禁止实例化。 */
    private DatabaseExecuteObservers() {
    }

    /**
     * 注册观察者。重复注册相同实例幂等（按对象引用去重）。
     *
     * @param observer 观察者，不可为 null
     */
    public static void register(DatabaseExecuteObserver observer) {
        if (observer == null) {
            return;
        }
        if (!OBSERVERS.contains(observer)) {
            OBSERVERS.add(observer);
        }
    }

    /**
     * 移除观察者（测试或生命周期管理用）。
     *
     * @param observer 待移除观察者
     * @return 是否实际移除
     */
    public static boolean unregister(DatabaseExecuteObserver observer) {
        return OBSERVERS.remove(observer);
    }

    /**
     * 清空全部观察者（测试隔离用）。
     */
    public static void clear() {
        OBSERVERS.clear();
    }

    /**
     * 当前注册的观察者数量。
     *
     * @return 观察者个数
     */
    public static int size() {
        return OBSERVERS.size();
    }

    /**
     * 通知全部观察者一次 SQL 执行完成。无观察者时零开销直接返回；
     * 观察者抛出的异常被吞噬并记录，不影响主链路。
     *
     * @param sql           SQL 文本
     * @param source        来源标识
     * @param executeMillis 耗时（毫秒）
     * @param rows          行数
     */
    public static void notifyExecuted(String sql, String source, long executeMillis, long rows) {
        List<DatabaseExecuteObserver> observers = OBSERVERS;
        if (observers.isEmpty()) {
            return;
        }
        for (DatabaseExecuteObserver observer : observers) {
            try {
                observer.onExecuteFinished(sql, source, executeMillis, rows);
            } catch (Throwable t) {
                logger.error("慢SQL观察者回调异常（已吞噬）: {}", t.getMessage(), t);
            }
        }
    }
}
