/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.biz.vo;

import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.sdk.model.Table;

/**
 * 表信息
 *
 * @Author 穿云
 * @Version 1.0.0
 * @Date 2026-01-08 21:41
 */
public final class TableVO extends Table {

    public String getColumnJson() {
        return JsonUtil.objToJson(super.getColumn());
    }

    public String getExtInfoJson() {
        return JsonUtil.objToJson(super.getExtInfo());
    }
}
