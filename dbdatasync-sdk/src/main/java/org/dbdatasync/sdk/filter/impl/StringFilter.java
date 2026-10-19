/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.sdk.filter.impl;

import org.dbdatasync.sdk.enums.FilterEnum;
import org.dbdatasync.sdk.enums.FilterTypeEnum;
import org.dbdatasync.sdk.filter.AbstractFilter;

public class StringFilter extends AbstractFilter {

    public StringFilter(String name, FilterEnum filterEnum, String value, boolean enableHighLightSearch) {
        setName(name);
        setFilter(filterEnum.getName());
        setValue(value);
        setEnableHighLightSearch(enableHighLightSearch);
    }

    @Override
    public FilterTypeEnum getFilterTypeEnum() {
        return FilterTypeEnum.STRING;
    }
}
