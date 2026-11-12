package org.dbdatasync.biz.service;

import org.dbdatasync.biz.vo.DatabaseSyncPerfAdviceVO;
import org.dbdatasync.biz.vo.DatabaseSyncPerfAdviceVO.BigTableAdvice;
import org.dbdatasync.biz.vo.DatabaseSyncPerfAdviceVO.DiffTableAdvice;

/**
 * 整库迁移性能适配报告服务（文档级建议，不自动执行 DDL）。
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
public interface DatabaseSyncPerfAdviceService {

    /**
     * 生成指定整库迁移任务的性能适配报告。
     *
     * @param taskId 整库迁移任务 ID
     * @return 性能适配报告 VO
     */
    DatabaseSyncPerfAdviceVO analyze(String taskId);
}
