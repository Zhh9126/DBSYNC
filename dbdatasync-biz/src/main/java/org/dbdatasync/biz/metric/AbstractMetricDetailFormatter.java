package org.dbdatasync.biz.metric;

import org.dbdatasync.biz.vo.MetricResponseVO;
import org.dbdatasync.common.util.CollectionUtils;

public abstract class AbstractMetricDetailFormatter implements MetricDetailFormatter {

    protected abstract void apply(MetricResponseVO vo);

    @Override
    public void format(MetricResponseVO vo) {
        if (CollectionUtils.isEmpty(vo.getMeasurements())) {
            return;
        }
        apply(vo);
    }
}
