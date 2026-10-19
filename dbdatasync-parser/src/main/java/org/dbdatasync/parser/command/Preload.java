/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.parser.command;

import org.dbdatasync.parser.ParserException;
import org.dbdatasync.parser.model.Connector;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.SystemConfig;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.parser.model.UserConfig;

/**
 * 预加载接口
 *
 * @Version 1.0.0
 * @Author AE86
 * @Date 2023-11-12 01:32
 */
public interface Preload {

    default SystemConfig parseSystemConfig() {
        throw new ParserException("Unsupported method parseSystemConfig");
    }

    default UserConfig parseUserConfig() {
        throw new ParserException("Unsupported method parseUserConfig");
    }

    default Connector parseConnector() {
        throw new ParserException("Unsupported method parseConnector");
    }

    default Mapping parseMapping() {
        throw new ParserException("Unsupported method parseMapping");
    }

    default TableGroup parseTableGroup() {
        throw new ParserException("Unsupported method parseTableGroup");
    }

    default Meta parseMeta() {
        throw new ParserException("Unsupported method parseMeta");
    }
}
