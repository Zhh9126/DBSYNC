package org.dbdatasync.biz.cutover;

/**
 * 回滚预案步骤。
 * <p>
 * 描述回滚预案中的单个执行步骤：步骤名称、具体动作说明，
 * 以及该步骤是否可逆（reversible）。不可逆步骤（如反向回灌
 * 写回数据）在执行前应由调用方做额外确认。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class RollbackStep {

    /** 步骤名称。 */
    private final String name;

    /** 具体动作描述。 */
    private final String action;

    /** 该步骤是否可逆。 */
    private final boolean reversible;

    /**
     * 构造一个回滚步骤。
     *
     * @param name       步骤名称，不允许为空
     * @param action     动作描述，不允许为空
     * @param reversible 是否可逆
     */
    public RollbackStep(String name, String action, boolean reversible) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("步骤名称不允许为空");
        }
        if (action == null || action.length() == 0) {
            throw new IllegalArgumentException("动作描述不允许为空");
        }
        this.name = name;
        this.action = action;
        this.reversible = reversible;
    }

    /**
     * 获取步骤名称。
     *
     * @return 步骤名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取动作描述。
     *
     * @return 动作描述
     */
    public String getAction() {
        return action;
    }

    /**
     * 判断该步骤是否可逆。
     *
     * @return 可逆返回 true
     */
    public boolean isReversible() {
        return reversible;
    }

    /**
     * 返回人类可读的单步描述。
     *
     * @return 形如 "步骤名：动作描述 [可逆/不可逆]" 的文本
     */
    @Override
    public String toString() {
        return name + "：" + action + " [" + (reversible ? "可逆" : "不可逆") + "]";
    }
}
