/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.retry;

/**
 * 同步失败明细的结构化标签。
 *
 * <p>失败明细表 {@code DBDataSync_task_detail_*} 的 {@code error} 列是全表唯一
 * 「长文本、可原地更新、不参与关联/统计/展示语义」的空闲字段。用它承载失败分类与
 * 死信终态，能在不新增列、不新增表、不改变 {@code isSuccess} 值域（0/1）的前提下，
 * 让失败队列具备「可自愈 / 需人工介入」的可检索语义。
 *
 * <p>标签形如：
 * <pre>
 *   [SYNC_FAIL][kind=TRANSIENT][retry=3] java.sql.SQLTimeoutException: ...
 *   [DEAD_LETTER][kind=PERMANENT] DuplicateKeyException: ...
 * </pre>
 *
 * @author wuji
 * @version 1.0.0
 */
public final class WriteFailureTag {

    public static final String SYNC_FAIL = "[SYNC_FAIL]";
    public static final String DEAD_LETTER = "[DEAD_LETTER]";
    public static final String KIND_PREFIX = "[kind=";
    public static final String RETRY_PREFIX = "[retry=";

    private WriteFailureTag() {
    }

    /**
     * 由异常生成分类片段，供写入链路 append 到 Result.error。
     */
    public static String describe(Throwable error) {
        WriteRetryPolicy.FailureKind kind = WriteRetryPolicy.classify(error);
        return KIND_PREFIX + kind.name() + "]";
    }

    /**
     * 提取整批错误文本里的失败分类。
     *
     * <p>一个批次可能同时包含多行、多种异常（Result.error 是批次共享的 StringBuffer）。
     * 采用「永久优先」的保守口径：只要批内出现任一永久故障，整批按永久故障处理，
     * 避免把不可恢复的行误标成可重试、造成无效重试与告警噪音。
     */
    public static String kindOf(String errorText) {
        if (errorText == null) {
            return WriteRetryPolicy.FailureKind.UNKNOWN.name();
        }
        if (errorText.contains(KIND_PREFIX + WriteRetryPolicy.FailureKind.PERMANENT.name() + "]")) {
            return WriteRetryPolicy.FailureKind.PERMANENT.name();
        }
        if (errorText.contains(KIND_PREFIX + WriteRetryPolicy.FailureKind.TRANSIENT.name() + "]")) {
            return WriteRetryPolicy.FailureKind.TRANSIENT.name();
        }
        int start = errorText.indexOf(KIND_PREFIX);
        if (start < 0) {
            return WriteRetryPolicy.FailureKind.UNKNOWN.name();
        }
        int from = start + KIND_PREFIX.length();
        int end = errorText.indexOf(']', from);
        return end < 0 ? WriteRetryPolicy.FailureKind.UNKNOWN.name() : errorText.substring(from, end);
    }

    public static boolean isPermanent(String errorText) {
        return WriteRetryPolicy.FailureKind.PERMANENT.name().equals(kindOf(errorText));
    }

    public static boolean isDeadLetter(String errorText) {
        return errorText != null && errorText.contains(DEAD_LETTER);
    }

    public static boolean isSyncFail(String errorText) {
        return errorText != null && errorText.contains(SYNC_FAIL);
    }

    /**
     * 为失败明细补齐标签：永久故障直接进死信，其余标记为待处理失败。
     * 已有死信标签的原样返回，保证幂等。
     *
     * @param errorText 原始错误文本
     * @param attempts  该行已执行的写入尝试次数（含首次）
     */
    public static String tag(String errorText, int attempts) {
        String body = errorText == null ? "" : errorText;
        // 已是死信终态则保持原样，保证幂等
        if (isDeadLetter(body)) {
            return body;
        }
        String kind = kindOf(body);
        // 去掉已有标签前缀，避免重复打标时标签堆叠
        if (body.startsWith(SYNC_FAIL)) {
            int cursor = SYNC_FAIL.length();
            while (cursor < body.length() && body.charAt(cursor) == '[') {
                int close = body.indexOf(']', cursor);
                if (close < 0) {
                    break;
                }
                cursor = close + 1;
            }
            body = body.substring(cursor).trim();
        } else {
            // 批次错误文本可能含多个分类片段，全部清理，避免标签堆叠
            StringBuilder cleaned = new StringBuilder();
            int cursor = 0;
            while (true) {
                int kindStart = body.indexOf(KIND_PREFIX, cursor);
                if (kindStart < 0) {
                    cleaned.append(body.substring(cursor));
                    break;
                }
                int kindEnd = body.indexOf(']', kindStart + KIND_PREFIX.length());
                if (kindEnd < 0) {
                    cleaned.append(body.substring(cursor));
                    break;
                }
                cleaned.append(body, cursor, kindStart);
                cursor = kindEnd + 1;
            }
            body = cleaned.toString().trim();
        }
        int safeAttempts = Math.max(0, attempts);
        String prefix;
        if (WriteRetryPolicy.FailureKind.PERMANENT.name().equals(kind)) {
            prefix = DEAD_LETTER + KIND_PREFIX + kind + "]" + RETRY_PREFIX + safeAttempts + "] ";
        } else {
            prefix = SYNC_FAIL + KIND_PREFIX + kind + "]" + RETRY_PREFIX + safeAttempts + "] ";
        }
        return prefix + body;
    }
}
