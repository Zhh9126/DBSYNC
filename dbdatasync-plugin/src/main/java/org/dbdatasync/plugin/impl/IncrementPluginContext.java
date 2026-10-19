package org.dbdatasync.plugin.impl;

import org.dbdatasync.sdk.enums.ModelEnum;
import org.dbdatasync.sdk.plugin.AbstractPluginContext;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2022/6/30 16:06
 */
public final class IncrementPluginContext extends AbstractPluginContext {

    @Override
    public ModelEnum getModelEnum() {
        return ModelEnum.INCREMENT;
    }
}
