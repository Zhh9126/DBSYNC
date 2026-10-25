/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.parser.ddl.alter;

import net.sf.jsqlparser.statement.alter.AlterExpression;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.ddl.AlterStrategy;
import org.dbdatasync.sdk.config.DDLConfig;
import org.dbdatasync.sdk.enums.DDLOperationEnum;

/**
 * 字段重命名: <code>ALTER TABLE t RENAME COLUMN old TO new</code>
 * <p>记录 旧名->新名 映射, 由 DDLParser.refreshFiledMappings 更新字段映射关系;
 * 跨源场景由 GeneralBufferActuator.buildCrossTypeDdl 生成目标方言 RENAME 语句。</p>
 */
public class RenameStrategy implements AlterStrategy {

    @Override
    public void parse(AlterExpression expression, DDLConfig ddlConfig) {
        String oldName = StringUtil.replace(expression.getColOldName(), StringUtil.BACK_QUOTE, StringUtil.EMPTY);
        oldName = StringUtil.replace(oldName, StringUtil.DOUBLE_QUOTATION, StringUtil.EMPTY);
        String newName = StringUtil.replace(expression.getColumnName(), StringUtil.BACK_QUOTE, StringUtil.EMPTY);
        newName = StringUtil.replace(newName, StringUtil.DOUBLE_QUOTATION, StringUtil.EMPTY);
        if (StringUtil.isNotBlank(oldName) && StringUtil.isNotBlank(newName)) {
            ddlConfig.getChangedFieldNames().put(oldName, newName);
            ddlConfig.setDdlOperationEnum(DDLOperationEnum.ALTER_CHANGE);
        }
    }
}
