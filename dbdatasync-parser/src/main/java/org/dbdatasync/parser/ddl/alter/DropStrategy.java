/**
 * DBDataSync Copyright 2020-2025 All Rights Reserved.
 */
package org.dbdatasync.parser.ddl.alter;

import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.ddl.AlterStrategy;
import org.dbdatasync.sdk.config.DDLConfig;
import org.dbdatasync.sdk.enums.DDLOperationEnum;

import net.sf.jsqlparser.statement.alter.AlterExpression;

import net.sf.jsqlparser.statement.alter.AlterExpression;

/**
 * 解析drop
 * <code>
 *     ALTER TABLE `test`.`test_user`
 * DROP COLUMN `aaa`,
 * DROP COLUMN `bbb`
 * </code>
 *
 * @author life
 */
public class DropStrategy implements AlterStrategy {

    @Override
    public void parse(AlterExpression expression, DDLConfig ddlConfig) {
        if (expression.getColumnName() != null) {
            String columnName = StringUtil.replace(expression.getColumnName(), StringUtil.BACK_QUOTE, StringUtil.EMPTY);
            columnName = StringUtil.replace(columnName, StringUtil.DOUBLE_QUOTATION, StringUtil.EMPTY);
            ddlConfig.getDroppedFieldNames().add(columnName);
        }
        ddlConfig.setDdlOperationEnum(DDLOperationEnum.ALTER_DROP);
    }
}
