package org.dbdatasync.biz.service.impl;

import org.dbdatasync.biz.service.DatabaseSyncPerfAdviceService;
import org.dbdatasync.biz.vo.DatabaseSyncPerfAdviceVO;
import org.dbdatasync.biz.vo.DatabaseSyncPerfAdviceVO.BigTableAdvice;
import org.dbdatasync.biz.vo.DatabaseSyncPerfAdviceVO.DiffTableAdvice;
import org.dbdatasync.common.model.Paging;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.TaskDetailProfile;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.model.TaskDetailQuery;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.spi.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 整库迁移性能适配报告实现。
 *
 * <p>数据来源：任务级 Meta（耗时/总量/成败）+ 明细（逐表 sourceTotal/targetTotal/diffTotal）+ 表组（表名）。
 * 产出为文档级建议（大表索引/分区、差异表补迁、读写参数调整、目标库参数），不自动执行 DDL。</p>
 *
 * <p>Wave10 Q1 唯一行数口径（本类为唯一收敛点）：{@code successRows=Meta.success}、
 * {@code failRows=Meta.fail}、{@code totalRows=successRows+failRows}（恒等，UI 标注「总数据量=成功+失败」）；
 * {@code metaTotal=Meta.total} 仅作核对字段，不参与报告主数值、也不改写 Meta 本身；
 * {@code rowsConsistent=(metaTotal==totalRows)}，不一致时结论追加核对提示；
 * {@code durationMs=max(0, endTime-beginTime)}；
 * {@code throughputPerSecond=round(successRows*1000.0/durationMs, 1)}（分子只用 successRows）；
 * 大表行数取该表明细 {@code sourceTotal}、差异表取明细 {@code diffTotal/failTotal}；
 * <strong>明细 sourceTotal 汇总禁止当作「总数据量」</strong>（原 totalSource Top10 死计算已删除）。</p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-11-05
 */
@Service
public class DatabaseSyncPerfAdviceServiceImpl implements DatabaseSyncPerfAdviceService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /** 大表判定阈值（源行数） */
    private static final long BIG_TABLE_THRESHOLD = 1_000_000L;

    /** 大表 TopN */
    private static final int BIG_TABLE_LIMIT = 10;

    @Resource
    private TaskService<DatabaseSyncTask> taskService;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private TaskDetailProfile taskDetailProfile;

    @Override
    public DatabaseSyncPerfAdviceVO analyze(String taskId) {
        Assert.hasText(taskId, "任务 ID 不能为空");
        DatabaseSyncTask task = taskService.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        DatabaseSyncPerfAdviceVO vo = new DatabaseSyncPerfAdviceVO();
        vo.setTaskId(taskId);
        vo.setTaskName(task.getName());

        // 任务级 Meta 为行数唯一权威源（Wave10 Q1）：
        // totalRows 由 success+fail 恒等派生，不再直接取 Meta.total；metaTotal 仅用于核对。
        Meta taskMeta = metaProfile.getMetaByTaskId(taskId, org.dbdatasync.common.enums.TaskLevelEnum.TASK);
        vo.setMigrated(taskMeta != null);
        long successRows = 0L;
        long failRows = 0L;
        long metaTotal = 0L;
        if (taskMeta != null) {
            vo.setDurationMs(Math.max(0L, taskMeta.getEndTime() - taskMeta.getBeginTime()));
            successRows = safe(taskMeta.getSuccess());
            failRows = safe(taskMeta.getFail());
            metaTotal = safe(taskMeta.getTotal());
        }
        vo.setSuccessRows(successRows);
        vo.setFailRows(failRows);
        vo.setTotalRows(successRows + failRows);
        vo.setMetaTotal(metaTotal);
        vo.setRowsConsistent(metaTotal == successRows + failRows);

        // 表级明细（逐表 sourceTotal/targetTotal/diffTotal/failTotal）
        List<TableDetail> details = collectTableDetails(taskId);
        vo.setTableCount(details.size());

        // 大表 TopN（行数取该表明细 sourceTotal；明细汇总禁止当作「总数据量」）
        List<TableDetail> sortedBySource = new ArrayList<>(details);
        sortedBySource.sort(Comparator.comparingLong(TableDetail::getSourceTotal).reversed());
        for (int i = 0; i < sortedBySource.size() && i < BIG_TABLE_LIMIT; i++) {
            TableDetail d = sortedBySource.get(i);
            if (d.getSourceTotal() >= BIG_TABLE_THRESHOLD) {
                vo.getBigTables().add(new BigTableAdvice(d.getTableName(), d.getSourceTotal(),
                        buildBigTableAdvice(d)));
            }
        }

        // 差异表（diffTotal>0 / failTotal>0，均取该表明细）
        long totalDiff = 0;
        for (TableDetail d : details) {
            if (d.getDiffTotal() > 0) {
                vo.getDiffTables().add(new DiffTableAdvice(d.getTableName(), d.getDiffTotal(),
                        "源/目标行数不一致（差 " + d.getDiffTotal() + " 行），建议重新执行数据一致性校验并补迁差异数据"));
                totalDiff += d.getDiffTotal();
            }
            if (d.getFailTotal() > 0) {
                vo.getDiffTables().add(new DiffTableAdvice(d.getTableName() + "（失败）", d.getFailTotal(),
                        "迁移失败 " + d.getFailTotal() + " 行，建议查看失败明细并重试"));
            }
        }

        // 参数调优建议
        vo.getParameterAdvices().addAll(buildParameterAdvices(task, vo));

        // 目标库参数建议（按目标方言，简单提示）
        vo.getTargetDbAdvices().addAll(buildTargetDbAdvices(task));

        // 净吞吐（行/s）：分子只用 successRows；durationMs<=0 或 successRows<=0 → 0
        if (vo.getDurationMs() > 0 && vo.getSuccessRows() > 0) {
            vo.setThroughputPerSecond(Math.round(vo.getSuccessRows() * 1000.0 / vo.getDurationMs() * 10) / 10.0);
        } else {
            vo.setThroughputPerSecond(0D);
        }

        // Wave11 方向五：成本/耗时/吞吐统计（纯派生：只回显任务配置 + 复用既有口径二次计算；
        // 不写 Meta、不改写 totalRows/successRows/failRows/metaTotal/rowsConsistent/durationMs/throughputPerSecond）
        fillCostMetrics(vo, task);

        // 结论
        vo.setConclusion(buildConclusion(vo, totalDiff));
        return vo;
    }

    private long safe(java.util.concurrent.atomic.AtomicLong v) {
        return v == null ? 0 : v.get();
    }

    private List<TableDetail> collectTableDetails(String taskId) {
        List<TableDetail> result = new ArrayList<>();
        // 表组（表名）
        List<TableGroup> tableGroups = new ArrayList<>();
        tableGroupProfile.pageScanTableGroups(taskId, 1000, tableGroups::addAll);
        // 明细（sourceTotal/targetTotal/diffTotal/failTotal）
        Paging paging = taskDetailProfile.queryResults(TaskDetailQuery.of(taskId).setPage(1, 100000));
        Map<String, Map<String, Long>> detailByTable = new java.util.HashMap<>();
        if (paging != null && !CollectionUtils.isEmpty(paging.getData())) {
            for (Object item : paging.getData()) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) item;
                String targetTable = str(row.get(ConfigConstant.DETAIL_TARGET_TABLE));
                if (StringUtil.isBlank(targetTable)) {
                    continue;
                }
                Map<String, Long> metrics = detailByTable.computeIfAbsent(targetTable, k -> new java.util.HashMap<>());
                // 明细行 data JSON 字段在 row 中为字符串或对象，取 sourceTotal/targetTotal/diffTotal/failTotal
                Object data = row.get(ConfigConstant.BINLOG_DATA);
                if (data instanceof Map) {
                    mergeMetrics(metrics, (Map) data);
                } else if (data instanceof String && !((String) data).isEmpty()) {
                    try {
                        Map m = org.dbdatasync.common.util.JsonUtil.parseMap((String) data);
                        if (m != null) {
                            mergeMetrics(metrics, m);
                        }
                    } catch (Exception ignore) {
                        // 明细 JSON 解析失败不影响整体（Wave9 降级分支，逐表跳过，不 NPE）
                    }
                }
            }
        }
        // 合并
        for (TableGroup tg : tableGroups) {
            String name = tg.getTargetTable() != null ? tg.getTargetTable().getName() : StringUtil.EMPTY;
            Map<String, Long> metrics = detailByTable.get(name);
            TableDetail d = new TableDetail();
            d.setTableName(name);
            d.setSourceTotal(metrics == null ? 0 : val(metrics.get("sourceTotal")));
            d.setTargetTotal(metrics == null ? 0 : val(metrics.get("targetTotal")));
            d.setDiffTotal(metrics == null ? 0 : val(metrics.get("diffTotal")));
            d.setFailTotal(metrics == null ? 0 : val(metrics.get("failTotal")));
            result.add(d);
        }
        return result;
    }

    private void mergeMetrics(Map<String, Long> metrics, Map<String, Object> data) {
        for (String key : new String[]{"sourceTotal", "targetTotal", "diffTotal", "failTotal"}) {
            Object v = data.get(key);
            if (v instanceof Number) {
                metrics.merge(key, ((Number) v).longValue(), Long::sum);
            } else if (v != null) {
                try {
                    metrics.merge(key, Long.parseLong(String.valueOf(v)), Long::sum);
                } catch (Exception ignore) {
                    // 忽略非法值
                }
            }
        }
    }

    private long val(Long v) {
        return v == null ? 0 : v;
    }

    private String str(Object o) {
        return o == null ? StringUtil.EMPTY : String.valueOf(o);
    }

    private String buildBigTableAdvice(TableDetail d) {
        StringBuilder sb = new StringBuilder();
        sb.append("源表数据量 ").append(d.getSourceTotal()).append(" 行（≥100 万）：");
        if (d.getSourceTotal() >= 10_000_000L) {
            sb.append("超千万级，建议目标库建分区表（按日期/主键范围分区）并按查询条件建联合索引；");
        } else {
            sb.append("建议目标库按高频查询列建索引，避免全表扫描；");
        }
        if (d.getDiffTotal() > 0) {
            sb.append("存在差异 ").append(d.getDiffTotal()).append(" 行，先补迁再优化。");
        }
        return sb.toString();
    }

    private List<String> buildParameterAdvices(DatabaseSyncTask task, DatabaseSyncPerfAdviceVO vo) {
        List<String> advices = new ArrayList<>();
        int read = task != null ? task.getReadNum() : 10000;
        int batch = task != null ? task.getBatchNum() : 1000;
        int thread = task != null ? task.getThreadNum() : 5;
        double throughput = vo.getThroughputPerSecond();
        advices.add("当前 readNum=" + read + "、batchNum=" + batch + "、threadNum=" + thread + "。" +
                "总耗时 " + formatMs(vo.getDurationMs()) + "、净吞吐 " + throughput + " 行/s。");
        if (throughput > 0 && throughput < 5000) {
            advices.add("吞吐偏低（<5000 行/s），可尝试增大 batchNum（如 2000~5000）与 threadNum（如表级并发 8~16），降低网络往返开销；");
        } else if (throughput > 50000) {
            advices.add("吞吐较高，可关注目标库 IO/写放大，避免磁盘瓶颈；");
        }
        if (read > 0 && read < 10000) {
            advices.add("readNum 较小（" + read + "），读取批小会增加往返，可提到 10000+ 观察吞吐；");
        }
        advices.add("以上参数调整仅作方向参考，需在测试环境复测后应用到生产任务。");
        return advices;
    }

    private List<String> buildTargetDbAdvices(DatabaseSyncTask task) {
        List<String> advices = new ArrayList<>();
        if (task == null || task.getDatabaseMappings() == null || task.getDatabaseMappings().isEmpty()) {
            advices.add("任务无库映射，跳过目标库参数建议。");
            return advices;
        }
        String targetConnectorId = null;
        for (Object dm : task.getDatabaseMappings()) {
            if (dm instanceof org.dbdatasync.sdk.model.DatabaseMapping) {
                org.dbdatasync.sdk.model.DatabaseMapping mapping = (org.dbdatasync.sdk.model.DatabaseMapping) dm;
                if (StringUtil.isNotBlank(mapping.getTargetConnectorId())) {
                    targetConnectorId = mapping.getTargetConnectorId();
                    break;
                }
            }
        }
        if (StringUtil.isBlank(targetConnectorId)) {
            advices.add("未识别目标连接器，无法给参数建议。");
            return advices;
        }
        String lower = targetConnectorId.toLowerCase();
        if (lower.contains("mysql")) {
            advices.add("目标 MySQL：如大批量写入，调大 max_allowed_packet（建议 64MB~256MB）、innodb_buffer_pool_size 至物理内存 60%~70%，关闭唯一键检查可在全量阶段提速（完成后校验）。");
        } else if (lower.contains("oracle") || lower.contains("dm")) {
            advices.add("目标 " + targetConnectorId + "：批量提交建议 1000~5000 行/次，合理设置 SGA/PGA、并行度与重做日志大小，大表按分区键建分区表。");
        } else if (lower.contains("postgresql") || lower.contains("kingbase")) {
            advices.add("目标 PostgreSQL 系：调大 work_mem/maintenance_work_mem、shared_buffers 至物理内存 25%~40%，批量 COPY 或 COPY + 分片并发可显著提速大表。");
        } else {
            advices.add("目标 " + targetConnectorId + "：请结合目标库官方参数指南，重点调批量提交大小、内存/缓冲池、并行度与索引维护策略。");
        }
        advices.add("建议 DDL 均在迁移完成后、业务切换前在测试环境验证（本轮仅输出文档级建议，不自动执行）。");
        return advices;
    }

    /**
     * 汇总结论：口径与数值自洽（Wave10 Q1）。
     *
     * @param vo        报告 VO（totalRows 恒等于 success+fail）
     * @param totalDiff 明细差异行合计（仅用于「是否存在差异表」判断，不参与总数据量）
     * @return 结论文案
     */
    private String buildConclusion(DatabaseSyncPerfAdviceVO vo, long totalDiff) {
        if (!vo.isMigrated()) {
            return "该任务尚未执行过迁移（无运行记录），请先启动迁移后再生成性能适配报告。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本次迁移总数据量 ").append(vo.getTotalRows()).append(" 行（成功 ").append(vo.getSuccessRows())
                .append("、失败 ").append(vo.getFailRows()).append("，总数据量=成功+失败），耗时 ")
                .append(formatMs(vo.getDurationMs()))
                .append("，净吞吐 ").append(vo.getThroughputPerSecond()).append(" 行/s。");
        if (vo.getFailRows() > 0 || totalDiff > 0) {
            sb.append("存在失败/差异数据，建议先解决数据一致性问题，再评估切换；");
        } else {
            sb.append("无失败行且无差异表，一致性良好，可按编排阶段进入校验与切换；");
        }
        if (!vo.isRowsConsistent()) {
            sb.append("任务级 Meta.total 与 成功+失败 不一致，请核对运行记录；");
        }
        if (!vo.getBigTables().isEmpty()) {
            sb.append("大表 ").append(vo.getBigTables().size()).append(" 张（≥100 万行），建议优先完成索引/分区设计。");
        }
        return sb.toString();
    }

    /**
     * Wave11 方向五：成本/耗时/吞吐统计（纯派生展示层）。
     *
     * <p>只读 {@code vo} 的 durationMs/successRows/totalRows/throughputPerSecond 与任务配置，
     * 回填 7 个派生字段；绝不写 Meta，也绝不改写 Wave10 Q1 已固化的
     * totalRows/successRows/failRows/metaTotal/rowsConsistent/durationMs/throughputPerSecond。</p>
     */
    private void fillCostMetrics(DatabaseSyncPerfAdviceVO vo, DatabaseSyncTask task) {
        // 1) 配置回显（task 为 null 或配置 <=0 时归一 0）
        long records = task == null ? 0L : Math.max(0L, task.getRecordsPerSecond());
        long bytes = task == null ? 0L : Math.max(0L, task.getBytesPerSecond());
        vo.setConfiguredRecordsPerSecond(records);
        vo.setConfiguredBytesPerSecond(bytes);

        // 2) 低峰时间窗文案
        boolean windowEnabled = task != null && task.isEnableOffPeakWindow();
        vo.setOffPeakWindowEnabled(windowEnabled);
        String windowText;
        if (!windowEnabled) {
            windowText = "未启用";
        } else {
            String start = task.getWindowStart();
            String end = task.getWindowEnd();
            windowText = StringUtil.isNotBlank(start) && StringUtil.isNotBlank(end)
                    ? start + " ~ " + end
                    : "未配置";
        }
        vo.setOffPeakWindowText(windowText);

        // 3) 每千行平均耗时 ms（每行 ms × 1000；durationMs<=0 或 successRows<=0 → 0，不除零）
        long durationMs = vo.getDurationMs();
        long successRows = vo.getSuccessRows();
        long totalRows = vo.getTotalRows();
        double avg = durationMs > 0 && successRows > 0
                ? Math.round(durationMs * 1000.0 / successRows * 10) / 10.0
                : 0D;
        vo.setAvgMsPerThousandRows(avg);

        // 4) 按配置行限速估算的理论最短耗时（用 totalRows，单位 ms；未限速或数据为 0 → 0）
        long estimated = records > 0 && totalRows > 0
                ? (long) Math.ceil(totalRows * 1000.0 / records)
                : 0L;
        vo.setEstimatedMinDurationMs(estimated);

        // 5) 一句中文摘要（复用既有 formatMs；不含任何与 Wave10 结论冲突的文案）
        StringBuilder sb = new StringBuilder();
        sb.append("本次迁移 ").append(totalRows).append(" 行 / 耗时 ")
                .append(formatMs(durationMs)).append(" / 净吞吐 ")
                .append(vo.getThroughputPerSecond()).append(" 行/s；");
        if (records > 0 || bytes > 0) {
            sb.append("已配置限速 ").append(records).append(" 行/s、")
                    .append(bytes).append(" 字节/s");
        } else {
            sb.append("未配置限速");
        }
        sb.append("，低峰时间窗 ").append(windowText);
        vo.setCostSummary(sb.toString());
    }

    private String formatMs(long ms) {
        if (ms <= 0) {
            return "-";
        }
        if (ms < 1000) {
            return ms + " ms";
        }
        double sec = ms / 1000.0;
        if (sec < 60) {
            return String.format("%.1f s", sec);
        }
        double min = sec / 60.0;
        if (min < 60) {
            return String.format("%.1f min", min);
        }
        return String.format("%.1f h", min / 60.0);
    }

    /** 表级明细（内部聚合） */
    private static final class TableDetail {
        private String tableName;
        private long sourceTotal;
        private long targetTotal;
        private long diffTotal;
        private long failTotal;

        String getTableName() {
            return tableName;
        }

        void setTableName(String tableName) {
            this.tableName = tableName;
        }

        long getSourceTotal() {
            return sourceTotal;
        }

        void setSourceTotal(long sourceTotal) {
            this.sourceTotal = sourceTotal;
        }

        long getTargetTotal() {
            return targetTotal;
        }

        void setTargetTotal(long targetTotal) {
            this.targetTotal = targetTotal;
        }

        long getDiffTotal() {
            return diffTotal;
        }

        void setDiffTotal(long diffTotal) {
            this.diffTotal = diffTotal;
        }

        long getFailTotal() {
            return failTotal;
        }

        void setFailTotal(long failTotal) {
            this.failTotal = failTotal;
        }
    }
}
