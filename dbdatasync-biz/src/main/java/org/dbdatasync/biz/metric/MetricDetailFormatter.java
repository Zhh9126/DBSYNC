package org.dbdatasync.biz.metric;

import org.dbdatasync.biz.vo.MetricResponseVO;

public interface MetricDetailFormatter {

    void format(MetricResponseVO vo);

}