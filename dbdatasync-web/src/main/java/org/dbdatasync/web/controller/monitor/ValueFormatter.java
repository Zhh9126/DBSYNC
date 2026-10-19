package org.dbdatasync.web.controller.monitor;

public interface ValueFormatter<T, R> {

    R formatValue(T value);
}
