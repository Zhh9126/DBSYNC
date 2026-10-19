package org.dbdatasync.parser.convert.handler;

import org.dbdatasync.parser.convert.AbstractHandler;

/**
 * Number转String
 *
 * @author AE86
 * @version 1.0.0
 * @date 2022/7/20 23:04
 */
public class NumberToStringHandler extends AbstractHandler {

    @Override
    public Object convert(String args, Object value) {
        return String.valueOf(value);
    }
}
