/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.observer;

/**
 * 数据库执行观察者（SPI）：引擎（{@code DatabaseTemplate}）每次 SQL 执行后回调，
 * 供运行时观测组件（慢 SQL 采集、审计、链路追踪等）无侵入挂接。
 *
 * <p>设计约束：</p>
 * <ul>
 *     <li>回调发生在 SQL 执行成功之后，执行异常不回调（失败执行不污染耗时分布）；</li>
 *     <li>观察者实现必须快速返回、不得抛异常影响主链路（抛出的运行时异常由
 *         {@code DatabaseExecuteObservers} 全部吞噬并记录）；</li>
 *     <li>默认无观察者时主链路零开销（空实现先行判断）。</li>
 * </ul>
 *
 * <p>接入方式：SDK 层定义本接口与静态注册点 {@link DatabaseExecuteObservers}，
 * 由上层（biz/web）在 Spring Bean 初始化期调用 {@link DatabaseExecuteObservers#register}
 * 注入实现；引擎代码零改造即可开启观测。</p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public interface DatabaseExecuteObserver {

    /**
     * SQL 执行完成回调（成功路径）。
     *
     * @param sql           执行的 SQL 文本（可能被驱动改写；可能为 null）
     * @param source        来源标识（任务 / 连接器 / 调用方自定义）
     * @param executeMillis 执行耗时（毫秒），执行成功的耗时
     * @param rows          影响或返回的行数（executeQuery 不遍历 ResultSet，通常为 0）
     */
    void onExecuteFinished(String sql, String source, long executeMillis, long rows);
}
