/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.parser.ddl;

import net.sf.jsqlparser.JSQLParserException;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.sdk.config.DDLConfig;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.spi.ConnectorService;

public interface DDLParser {

    DDLConfig parse(ConnectorInstance connectorInstance, ConnectorService connectorService, TableGroup tableGroup, String sql) throws JSQLParserException;

    void refreshFiledMappings(TableGroup tableGroup, DDLConfig targetDDLConfig);
}
