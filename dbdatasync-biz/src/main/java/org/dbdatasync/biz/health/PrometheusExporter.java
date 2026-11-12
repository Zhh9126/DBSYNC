/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.health;

import org.dbdatasync.biz.model.ReplicationHealthReport;
import org.dbdatasync.biz.vo.LinkHealthVO;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Locale;

/**
 * Prometheus 文本格式（0.0.4）导出器，纯 JDK 实现，不引入 micrometer 依赖。
 *
 * <p>专业同步平台的运维接入基线：把任务状态、链路停滞、失败/成功计数、
 * 自动重试挽回、死信与 JVM 指标以标准文本暴露，供 Prometheus / VictoriaMetrics
 * 直接抓取并配置告警规则。
 *
 * @author wuji
 * @version 1.0.0
 */
public final class PrometheusExporter {

    private PrometheusExporter() {
    }

    public static String render(ReplicationHealthReport report) {
        StringBuilder sb = new StringBuilder();
        if (report == null) {
            return "# DBDataSync metrics unavailable\n";
        }

        header(sb, "dbdatasync_up", "控制台是否可用", "gauge");
        sample(sb, "dbdatasync_up", null, 1);

        header(sb, "dbdatasync_links", "链路数量按状态统计", "gauge");
        sample(sb, "dbdatasync_links", "state=\"total\"", report.getTotalLinks());
        sample(sb, "dbdatasync_links", "state=\"running\"", report.getRunningLinks());
        sample(sb, "dbdatasync_links", "state=\"healthy\"", report.getHealthyLinks());
        sample(sb, "dbdatasync_links", "state=\"warn\"", report.getWarnLinks());
        sample(sb, "dbdatasync_links", "state=\"critical\"", report.getCriticalLinks());
        sample(sb, "dbdatasync_links", "state=\"stale\"", report.getStaleLinks());
        sample(sb, "dbdatasync_links", "state=\"idle\"", report.getIdleLinks());

        header(sb, "dbdatasync_health_score", "运行链路平均健康分", "gauge");
        sample(sb, "dbdatasync_health_score", null, report.getHealthScore());
        header(sb, "dbdatasync_events_total", "进程启动以来接收的增量事件总数", "counter");
        sample(sb, "dbdatasync_events_total", null, report.getEventsTotal());
        header(sb, "dbdatasync_retry_recovered_total", "自动重试挽回的行数", "counter");
        sample(sb, "dbdatasync_retry_recovered_total", null, report.getRetryRecoveredTotal());
        header(sb, "dbdatasync_dead_letters_total", "进入死信终态的行数", "counter");
        sample(sb, "dbdatasync_dead_letters_total", null, report.getDeadLetterTotal());
        header(sb, "dbdatasync_max_stall_seconds", "最长链路停滞秒数", "gauge");
        sample(sb, "dbdatasync_max_stall_seconds", null, report.getMaxStallSeconds());
        header(sb, "dbdatasync_max_lag_seconds", "最长真实端到端复制延迟秒数（仅源端事件时间可用时统计）", "gauge");
        sample(sb, "dbdatasync_max_lag_seconds", null, report.getMaxLagSeconds());

        header(sb, "dbdatasync_link_state", "任务运行态 0就绪 1运行 2停止中 3完成", "gauge");
        header(sb, "dbdatasync_link_health_score", "单链路健康分", "gauge");
        header(sb, "dbdatasync_link_stall_seconds", "链路距最后一次事件/位点推进的秒数", "gauge");
        header(sb, "dbdatasync_link_last_event_timestamp_seconds", "最后一次事件的墙钟时间戳", "gauge");
        header(sb, "dbdatasync_link_lag_seconds", "端到端复制延迟秒数；source=source 时为真实值，其余为参考值", "gauge");
        header(sb, "dbdatasync_link_last_source_event_timestamp_seconds", "最后一次源端提交时间戳；0 表示连接器未提供", "gauge");
        header(sb, "dbdatasync_link_records", "累计处理记录数（total/success/fail）", "counter");
        header(sb, "dbdatasync_link_events_total", "单链路接收事件数", "counter");
        header(sb, "dbdatasync_link_retry_recovered_total", "单链路自动重试挽回行数", "counter");
        header(sb, "dbdatasync_link_dead_letters", "单链路死信数", "gauge");

        if (report.getLinks() != null) {
            for (LinkHealthVO link : report.getLinks()) {
                if (link == null) {
                    continue;
                }
                String labels = "meta_id=\"" + escape(link.getMetaId()) + "\",mapping=\""
                        + escape(link.getMappingName()) + "\"";
                sample(sb, "dbdatasync_link_state", labels, link.getState());
                sample(sb, "dbdatasync_link_health_score", labels, link.getScore());
                sample(sb, "dbdatasync_link_stall_seconds", labels, link.getStallSeconds());
                sample(sb, "dbdatasync_link_last_event_timestamp_seconds", labels,
                        link.getLastEventTime() / 1000d);
                String lagLabels = labels + ",source=\"" + escape(link.getLagSource()) + "\"";
                sample(sb, "dbdatasync_link_lag_seconds", lagLabels, link.getLagSeconds());
                sample(sb, "dbdatasync_link_last_source_event_timestamp_seconds", labels,
                        link.getLastSourceEventTime() / 1000d);
                sample(sb, "dbdatasync_link_records", labels + ",result=\"total\"", link.getTotal());
                sample(sb, "dbdatasync_link_records", labels + ",result=\"success\"", link.getSuccess());
                sample(sb, "dbdatasync_link_records", labels + ",result=\"fail\"", link.getFail());
                sample(sb, "dbdatasync_link_events_total", labels, link.getEventsTotal());
                sample(sb, "dbdatasync_link_retry_recovered_total", labels, link.getRetryRecovered());
                sample(sb, "dbdatasync_link_dead_letters", labels, link.getDeadLetters());
            }
        }

        appendJvm(sb);
        return sb.toString();
    }

    private static void appendJvm(StringBuilder sb) {
        try {
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            header(sb, "dbdatasync_jvm_memory_used_bytes", "JVM 堆内存已用字节", "gauge");
            sample(sb, "dbdatasync_jvm_memory_used_bytes", null,
                    memory.getHeapMemoryUsage().getUsed());
            header(sb, "dbdatasync_jvm_memory_max_bytes", "JVM 堆内存上限字节", "gauge");
            sample(sb, "dbdatasync_jvm_memory_max_bytes", null,
                    memory.getHeapMemoryUsage().getMax());
            header(sb, "dbdatasync_jvm_threads_current", "JVM 当前线程数", "gauge");
            sample(sb, "dbdatasync_jvm_threads_current", null, threads.getThreadCount());
            header(sb, "dbdatasync_jvm_uptime_seconds", "进程运行秒数", "counter");
            sample(sb, "dbdatasync_jvm_uptime_seconds", null, runtime.getUptime() / 1000d);
        } catch (Throwable ignored) {
            // JVM 指标不可用时不影响核心链路指标
        }
    }

    private static void header(StringBuilder sb, String metric, String help, String type) {
        sb.append("# HELP ").append(metric).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(metric).append(' ').append(type).append('\n');
    }

    private static void sample(StringBuilder sb, String metric, String labels, long value) {
        sb.append(metric);
        if (labels != null && labels.length() > 0) {
            sb.append('{').append(labels).append('}');
        }
        sb.append(' ').append(value).append('\n');
    }

    private static void sample(StringBuilder sb, String metric, String labels, double value) {
        sb.append(metric);
        if (labels != null && labels.length() > 0) {
            sb.append('{').append(labels).append('}');
        }
        sb.append(' ').append(String.format(Locale.ROOT, "%.3f", value)).append('\n');
    }

    /** Prometheus label value 转义：反斜杠、双引号、换行 */
    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }
}
