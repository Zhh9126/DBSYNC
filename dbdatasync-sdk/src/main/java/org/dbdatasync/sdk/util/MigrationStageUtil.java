/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.util;

import org.dbdatasync.common.util.StringUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 整库迁移编排阶段工具：阶段链定义、展示文案、非法值兜底与推进。
 *
 * <p>阶段链：schedule(编排就绪) → full(全量迁移) → increment(增量追平) → validate(一致性校验) → switchover(切换)。
 * 阶段持久化到任务级 Meta.SNAPSHOT（键 {@code migrationStage}），由
 * {@code DatabaseSyncServiceImpl} 读写；本工具仅做纯计算，便于单元测试。</p>
 *
 * @author wuji
 * @version 1.0.0
 */
public final class MigrationStageUtil {

    /** Meta.SNAPSHOT 持久化键 */
    public static final String SNAPSHOT_KEY_STAGE = "migrationStage";

    /** 阶段链（顺序即推进顺序） */
    public static final List<String> STAGES = Collections.unmodifiableList(Arrays.asList(
            "schedule", "full", "increment", "validate", "switchover"));

    /** 阶段文案（前端徽标展示） */
    public static final Map<String, String> STAGE_LABELS;

    static {
        Map<String, String> labels = new HashMap<>();
        labels.put("schedule", "编排就绪");
        labels.put("full", "全量迁移");
        labels.put("increment", "增量追平");
        labels.put("validate", "一致性校验");
        labels.put("switchover", "切换");
        STAGE_LABELS = Collections.unmodifiableMap(labels);
    }

    private MigrationStageUtil() {
    }

    /**
     * 归一化阶段：未知/空值回落 schedule。
     */
    public static String normalize(String stage) {
        if (StringUtil.isBlank(stage)) {
            return STAGES.get(0);
        }
        String s = stage.trim().toLowerCase();
        return STAGES.contains(s) ? s : STAGES.get(0);
    }

    /**
     * 是否为合法阶段码。
     */
    public static boolean isValid(String stage) {
        return !StringUtil.isBlank(stage) && STAGES.contains(stage.trim().toLowerCase());
    }

    /**
     * 推进后的阶段码：链内正常前进；末位（switchover）返回自身。
     */
    public static String advance(String stage) {
        String cur = normalize(stage);
        int idx = STAGES.indexOf(cur);
        if (idx < 0 || idx >= STAGES.size() - 1) {
            return cur;
        }
        return STAGES.get(idx + 1);
    }

    /**
     * 阶段文案（未知回落「编排就绪」）。
     */
    public static String label(String stage) {
        String cur = normalize(stage);
        return STAGE_LABELS.getOrDefault(cur, cur);
    }

    /**
     * 全部阶段（有序）。
     */
    public static Collection<String> all() {
        return STAGES;
    }
}
