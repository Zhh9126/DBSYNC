/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.sdk.filter.impl;

import org.dbdatasync.sdk.enums.FilterEnum;
import org.dbdatasync.sdk.enums.FilterTypeEnum;
import org.dbdatasync.sdk.filter.AbstractFilter;

import java.util.Objects;

public class LongFilter extends AbstractFilter {

    public LongFilter(String name, FilterEnum filterEnum, long value) {
        setName(name);
        setFilter(filterEnum.getName());
        setValue(Objects.toString(value));
    }

    @Override
    public FilterTypeEnum getFilterTypeEnum() {
        return FilterTypeEnum.LONG;
    }
}
