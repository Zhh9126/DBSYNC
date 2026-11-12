/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * @author wuji
 * @version 1.0.0
 * @date 2026-07-10 17:47
 */
public final class TaskSplitUtil {

    public static <T> void split(List<T> rows, int batchNum, Consumer<List<T>> consumer) {
        if (CollectionUtils.isEmpty(rows)) {
            return;
        }
        int effectiveBatch = batchNum > 0 ? batchNum : rows.size();
        int total = rows.size();
        // Wave30：stream().skip(from) 对顺序 List 是 O(from)，整批拆分会退化成 O(n²/batch)。
        // subList 是 O(1) 视图；复制出独立 ArrayList 避免子批与源 List 共享导致并发改动互相影响。
        for (int from = 0; from < total; from += effectiveBatch) {
            int to = (int) Math.min((long) from + effectiveBatch, (long) total);
            consumer.accept(new ArrayList<T>(rows.subList(from, to)));
        }
    }
}
