package org.dbdatasync.parser.convert.handler;

import org.dbdatasync.common.util.DateFormatUtil;
import org.dbdatasync.parser.convert.AbstractHandler;

/**
 * 字符串转Timestamp
 *
 * @author AE86
 * @version 1.0.0
 * @date 2022/7/12 23:04
 */
public class StringToTimestampHandler extends AbstractHandler {

    @Override
    public Object convert(String args, Object value) {
        if (value instanceof String) {
            String s = (String) value;
            return DateFormatUtil.stringToTimestamp(s);
        }
        return value;
    }
}
