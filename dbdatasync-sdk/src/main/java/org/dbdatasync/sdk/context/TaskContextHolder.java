/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.context;

/**
 * Wave24：任务级上下文（当前线程正在处理的任务 ID）。
 *
 * <p>Wave21 引擎慢 SQL 插桩只能拿到连接驱动类名，无法回答「这条慢 SQL 属于哪个同步任务」。
 * 本 Holder 由 {@code GeneralBufferActuator} 在分发任务时写入，在
 * {@code ParserComponentImpl.writeBatch} 提交到线程池的每个子任务里显式传递
 * （线程池线程不会继承 ThreadLocal，必须在子线程内 set/clear），
 * 最终由 {@code DatabaseTemplate} 的执行观察点拼进 source 标识。</p>
 *
 * <p>严格 try/finally 清理，避免线程池复用导致任务 ID 串味。</p>
 *
 * @author DBDataSync
 * @version 2.1.5
 */
public final class TaskContextHolder {

    private static final ThreadLocal<String> TASK_ID = new ThreadLocal<>();

    private TaskContextHolder() {
    }

    public static void set(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            TASK_ID.remove();
        } else {
            TASK_ID.set(taskId);
        }
    }

    public static String currentTaskId() {
        return TASK_ID.get();
    }

    public static void clear() {
        TASK_ID.remove();
    }
}
