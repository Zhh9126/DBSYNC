package org.dbdatasync.common.model;

/**
 * Wave24：单行写入失败的精确分类结果。
 *
 * <p>Wave22 的失败分类是批次口径：{@code Result.error} 是共享 StringBuffer，
 * 混合错误时「永久优先」会把整批都标成死信，导致同批次里瞬时故障的行被误判为
 * 不可恢复，无法自愈重放。本模型在逐条写入点记录每行自己的错误码、SQLState、
 * 分类与尝试次数，落失败明细时按行覆盖批次标签。</p>
 *
 * <p>放在 common 模块（不依赖 sdk），{@code kind} 用字符串承载，避免模块循环依赖。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class RowFailure {

    /** TRANSIENT / PERMANENT / UNKNOWN */
    private final String kind;
    private final int errorCode;
    private final String sqlState;
    private final int attempts;
    private final String message;

    public RowFailure(String kind, int errorCode, String sqlState, int attempts, String message) {
        this.kind = kind == null ? "UNKNOWN" : kind;
        this.errorCode = errorCode;
        this.sqlState = sqlState;
        this.attempts = attempts;
        this.message = message == null ? "" : message;
    }

    public String getKind() {
        return kind;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getSqlState() {
        return sqlState;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getMessage() {
        return message;
    }

    public boolean isPermanent() {
        return "PERMANENT".equals(kind);
    }
}
