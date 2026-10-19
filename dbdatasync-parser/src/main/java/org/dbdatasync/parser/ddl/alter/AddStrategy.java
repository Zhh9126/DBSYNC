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
 * 新增字段
 * <code>
 *     ALTER TABLE `test`.`test_user`
 * ADD COLUMN `aaa` varchar(255) NULL AFTER `create_date`,
 * ADD COLUMN `bbb` varchar(255) NULL AFTER `aaa`
 * </code>
 *
 * @author life
 */
public class AddStrategy implements AlterStrategy {

    @Override
    public void parse(AlterExpression expression, DDLConfig ddlConfig) {
        if (expression.getColDataTypeList() != null) {
            for (AlterExpression.ColumnDataType columnDataType : expression.getColDataTypeList()) {
                String columName = columnDataType.getColumnName();
                columName = StringUtil.replace(columName, StringUtil.BACK_QUOTE, StringUtil.EMPTY);
                columName = StringUtil.replace(columName, StringUtil.DOUBLE_QUOTATION, StringUtil.EMPTY);
                ddlConfig.getAddedFieldNames().add(columName);
            }
        }
        ddlConfig.setDdlOperationEnum(DDLOperationEnum.ALTER_ADD);
    }
}
