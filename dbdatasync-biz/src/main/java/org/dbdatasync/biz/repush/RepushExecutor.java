/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.repush;

/**
 * 重推执行器 SPI：负责真正把一个批次的数据重推到目标端。
 * <p>
 * 本模块只交付纯逻辑编排，具体写入由实现类对接连接器 writer 完成
 * （例如调用既有同步组件将 mergedPayload 拆行写入目标表）。
 * 实现方约定：返回成功重推的行数；返回负数或抛出异常均视为该批失败。
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public interface RepushExecutor {

    /**
     * 执行一个批次的重推。
     *
     * @param batch 待重推的批次
     * @return 成功重推的行数；返回负数视为该批失败
     * @throws Exception 实现方可在写入失败时抛出异常，视为该批失败
     */
    int push(RepushBatch batch) throws Exception;
}
