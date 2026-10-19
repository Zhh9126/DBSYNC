package org.dbdatasync.biz.metric.impl;

import org.dbdatasync.biz.metric.AbstractMetricDetailFormatter;
import org.dbdatasync.biz.model.Sample;
import org.dbdatasync.biz.vo.MetricResponseVO;

public final class ValueMetricDetailFormatter extends AbstractMetricDetailFormatter {

    @Override
    public void apply(MetricResponseVO vo) {
        Sample sample = vo.getMeasurements().get(0);
        vo.setDetail(String.valueOf(sample.getValue()));
    }

}