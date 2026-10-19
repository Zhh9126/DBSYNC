/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.biz;

import org.dbdatasync.biz.vo.ConditionVO;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2020/1/19 16:02
 */
public interface ConditionService {

    /**
     * 获取过滤条件
     *
     * @return
     */
    ConditionVO getCondition();

}