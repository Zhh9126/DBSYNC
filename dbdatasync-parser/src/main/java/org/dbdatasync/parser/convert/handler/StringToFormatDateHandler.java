/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.parser.convert.handler;

import org.dbdatasync.common.util.DateFormatUtil;
import org.dbdatasync.parser.convert.AbstractHandler;

import java.text.ParseException;
import java.time.format.DateTimeFormatter;

/**
 * 字符串转日期自定义格式
 *
 * @author wuji
 */
public class StringToFormatDateHandler extends AbstractHandler {

    @Override
    public Object convert(String args, Object value) throws ParseException {

        if (value instanceof String) {
            String s = (String) value;
            return DateFormatUtil.stringToDate(s, DateTimeFormatter.ofPattern(args));
        }
        return value;
    }
}
