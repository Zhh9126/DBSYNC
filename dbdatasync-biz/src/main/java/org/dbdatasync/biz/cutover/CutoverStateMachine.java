package org.dbdatasync.biz.cutover;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切流状态机。
 * <p>
 * 维护 {@link CutoverPhase} 之间的合法迁移关系，采用「单向推进 + 回滚链」模型：
 * <pre>
 * 正向主链：INIT -&gt; FULL_SYNC_DONE -&gt; INCREMENT_SYNCING -&gt; SHADOW_WRITING
 *           -&gt; VERIFYING -&gt; SWITCHING -&gt; SWITCHED
 * 回滚链：  SWITCHED -&gt; ROLLING_BACK -&gt; ROLLED_BACK
 *           SWITCHING -&gt; ROLLING_BACK
 *           SHADOW_WRITING -&gt; ROLLING_BACK
 * 失败链：  SHADOW_WRITING / VERIFYING / SWITCHING -&gt; FAILED
 * 重走链：  ROLLED_BACK -&gt; INCREMENT_SYNCING（重走增量）
 * </pre>
 * 仅做纯状态推进与迁移历史记录，不涉及任何 JDBC / HTTP 操作；
 * 真正执行切流、双写由调用方通过 SPI 接入。
 * </p>
 *
 * @author DBDataSync
 * @version 2.1.4
 * @date 2026-11-07
 */
public class CutoverStateMachine {

    /** 历史记录中时间戳与迁移描述的分隔符。 */
    public static final String HISTORY_SEPARATOR = "|";

    /** 历史记录中源相位与目标相位之间的箭头。 */
    public static final String HISTORY_ARROW = "→";

    /** 内置合法迁移表：源相位 -&gt; 允许到达的目标相位集合。 */
    private static final Map<CutoverPhase, Set<CutoverPhase>> TRANSITIONS;

    static {
        Map<CutoverPhase, Set<CutoverPhase>> table = new HashMap<CutoverPhase, Set<CutoverPhase>>();
        table.put(CutoverPhase.INIT, setOf(CutoverPhase.FULL_SYNC_DONE));
        table.put(CutoverPhase.FULL_SYNC_DONE, setOf(CutoverPhase.INCREMENT_SYNCING));
        table.put(CutoverPhase.INCREMENT_SYNCING, setOf(CutoverPhase.SHADOW_WRITING));
        table.put(CutoverPhase.SHADOW_WRITING,
                setOf(CutoverPhase.VERIFYING, CutoverPhase.ROLLING_BACK, CutoverPhase.FAILED));
        table.put(CutoverPhase.VERIFYING, setOf(CutoverPhase.SWITCHING, CutoverPhase.FAILED));
        table.put(CutoverPhase.SWITCHING,
                setOf(CutoverPhase.SWITCHED, CutoverPhase.ROLLING_BACK, CutoverPhase.FAILED));
        table.put(CutoverPhase.SWITCHED, setOf(CutoverPhase.ROLLING_BACK));
        table.put(CutoverPhase.ROLLING_BACK, setOf(CutoverPhase.ROLLED_BACK));
        table.put(CutoverPhase.ROLLED_BACK, setOf(CutoverPhase.INCREMENT_SYNCING));
        table.put(CutoverPhase.FAILED, Collections.<CutoverPhase>emptySet());
        TRANSITIONS = Collections.unmodifiableMap(table);
    }

    /** 当前所处相位。 */
    private CutoverPhase current;

    /** 迁移历史，格式为 "epochMillis|FROM→TO"，按迁移发生顺序追加。 */
    private final List<String> history = new ArrayList<String>();

    /**
     * 默认构造，初始相位为 {@link CutoverPhase#INIT}。
     */
    public CutoverStateMachine() {
        this(CutoverPhase.INIT);
    }

    /**
     * 指定初始相位构造。
     *
     * @param initial 初始相位，不允许为 null
     */
    public CutoverStateMachine(CutoverPhase initial) {
        if (initial == null) {
            throw new IllegalArgumentException("初始相位不允许为 null");
        }
        this.current = initial;
    }

    /**
     * 判断 from 相位是否允许迁移到 to 相位。
     *
     * @param from 源相位
     * @param to   目标相位
     * @return 允许迁移返回 true，否则返回 false
     */
    public boolean canTransition(CutoverPhase from, CutoverPhase to) {
        if (from == null || to == null) {
            return false;
        }
        Set<CutoverPhase> targets = TRANSITIONS.get(from);
        return targets != null && targets.contains(to);
    }

    /**
     * 从当前相位迁移到目标相位。
     * <p>
     * 迁移合法则更新当前相位并追加历史记录；迁移非法则抛出
     * {@link IllegalStateException}，异常消息同时包含当前相位与目标相位，
     * 且状态保持不变。
     * </p>
     *
     * @param to 目标相位
     * @return 迁移后的当前相位
     * @throws IllegalStateException 当前相位不允许迁移到目标相位时抛出
     */
    public synchronized CutoverPhase transition(CutoverPhase to) {
        if (!canTransition(current, to)) {
            throw new IllegalStateException("非法相位迁移：当前相位[" + current
                    + "]不允许迁移到目标相位[" + to + "]，"
                    + "当前相位说明[" + current.getDesc() + "]");
        }
        CutoverPhase from = current;
        current = to;
        history.add(System.currentTimeMillis() + HISTORY_SEPARATOR + from + HISTORY_ARROW + to);
        return current;
    }

    /**
     * 获取迁移历史的只读副本，格式为 "epochMillis|FROM→TO"，
     * 按迁移发生顺序排列。
     *
     * @return 历史记录列表（只读副本）
     */
    public synchronized List<String> history() {
        return Collections.unmodifiableList(new ArrayList<String>(history));
    }

    /**
     * 获取当前相位。
     *
     * @return 当前相位
     */
    public synchronized CutoverPhase current() {
        return current;
    }

    /**
     * 构造不可变目标相位集合的辅助方法。
     *
     * @param targets 目标相位数组
     * @return 不可变集合
     */
    private static Set<CutoverPhase> setOf(CutoverPhase... targets) {
        return Collections.unmodifiableSet(new HashSet<CutoverPhase>(Arrays.asList(targets)));
    }
}
