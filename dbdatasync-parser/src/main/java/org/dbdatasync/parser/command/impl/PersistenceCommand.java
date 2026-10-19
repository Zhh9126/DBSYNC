/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.parser.command.impl;

import org.dbdatasync.parser.command.Command;
import org.dbdatasync.sdk.enums.StorageEnum;
import org.dbdatasync.sdk.storage.StorageService;

import java.util.Map;

/**
 * 配置序列化
 *
 * @Version 1.0.0
 * @Author AE86
 * @Date 2023-11-12 01:32
 */
public final class PersistenceCommand implements Command {

    private StorageService storageService;

    private Map params;

    public PersistenceCommand(StorageService storageService, Map params) {
        this.storageService = storageService;
        this.params = params;
    }

    @Override
    public boolean addConfig() {
        storageService.add(StorageEnum.CONFIG, params);
        return true;
    }

    @Override
    public boolean editConfig() {
        storageService.edit(StorageEnum.CONFIG, params);
        return true;
    }
}
