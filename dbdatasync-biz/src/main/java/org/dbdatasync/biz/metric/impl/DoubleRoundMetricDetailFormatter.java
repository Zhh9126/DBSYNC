package org.dbdatasync.biz.metric.impl;

import org.dbdatasync.biz.metric.AbstractMetricDetailFormatter;
import org.dbdatasync.biz.model.Sample;
import org.dbdatasync.biz.vo.MetricResponseVO;

public final class DoubleRoundMetricDetailFormatter extends AbstractMetricDetailFormatter {

    @Override
    public void apply(MetricResponseVO vo) {
        Sample sample = vo.getMeasurements().get(0);
        long round = Math.round((Double) sample.getValue());
        vo.setDetail(String.valueOf(round));
    }

}