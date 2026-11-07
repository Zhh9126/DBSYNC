package org.dbdatasync.biz.cutover;

/**
 * 切流阶段枚举。
 * <p>
 * 描述一次「源端 -> 目标端」数据迁移割接的完整生命周期阶段：
 * 初始化、全量完成、增量同步中、影子写、校验中、切流中、已切换、
 * 回滚中、已回滚、失败。枚举本身只承载阶段语义与中文说明，
 * 合法迁移关系由 {@link CutoverStateMachine} 统一维护。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public enum CutoverPhase {

    /** 初始化：全量同步尚未开始或尚未完成，流量仍在源端。 */
    INIT("初始化，全量同步未完成"),

    /** 全量同步完成：历史数据已拷贝，可开启增量同步。 */
    FULL_SYNC_DONE("全量同步完成"),

    /** 增量同步中：持续追平源端增量数据。 */
    INCREMENT_SYNCING("增量同步中"),

    /** 影子写中：双写比对阶段，目标端影子写与源端写入逐条比对。 */
    SHADOW_WRITING("影子写比对中"),

    /** 校验中：对两端数据做最终一致性校验。 */
    VERIFYING("一致性校验中"),

    /** 切流中：正在把业务流量从源端切到目标端。 */
    SWITCHING("流量切换中"),

    /** 已切换：流量已稳定落在目标端，进入观察期。 */
    SWITCHED("已切换至目标端"),

    /** 回滚中：正在执行回滚预案步骤。 */
    ROLLING_BACK("回滚执行中"),

    /** 已回滚：流量已切回源端，可重走增量同步。 */
    ROLLED_BACK("已回滚至源端"),

    /** 失败：割接过程出现不可继续的失败。 */
    FAILED("割接失败");

    /** 阶段中文说明。 */
    private final String desc;

    CutoverPhase(String desc) {
        this.desc = desc;
    }

    /**
     * 获取阶段中文说明。
     *
     * @return 阶段说明文本
     */
    public String getDesc() {
        return desc;
    }
}
