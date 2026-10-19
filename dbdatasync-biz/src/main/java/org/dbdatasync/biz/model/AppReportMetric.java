/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.biz.model;

import org.dbdatasync.biz.vo.CpuVO;
import org.dbdatasync.biz.vo.DiskSpaceVO;
import org.dbdatasync.biz.vo.MemoryVO;
import org.dbdatasync.biz.vo.MetricResponseVO;
import org.dbdatasync.biz.vo.TpsVO;

import java.util.List;

public class AppReportMetric {

    /**
     * 待处理数
     */
    private long queueUp;

    /**
     * 队列长度
     */
    private long queueCapacity;

    /**
     * 持久化待处理数
     */
    private long storageQueueUp;

    /**
     * 持久化队列长度
     */
    private long storageQueueCapacity;

    /**
     * 统计执行器TPS
     */
    private TpsVO tps;

    private CpuVO cpu;

    private MemoryVO memory;

    private DiskSpaceVO disk;

    private List<MetricResponseVO> metrics;

    public long getQueueUp() {
        return queueUp;
    }

    public void setQueueUp(long queueUp) {
        this.queueUp = queueUp;
    }

    public long getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(long queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getStorageQueueUp() {
        return storageQueueUp;
    }

    public void setStorageQueueUp(long storageQueueUp) {
        this.storageQueueUp = storageQueueUp;
    }

    public long getStorageQueueCapacity() {
        return storageQueueCapacity;
    }

    public void setStorageQueueCapacity(long storageQueueCapacity) {
        this.storageQueueCapacity = storageQueueCapacity;
    }

    public TpsVO getTps() {
        return tps;
    }

    public void setTps(TpsVO tps) {
        this.tps = tps;
    }

    public CpuVO getCpu() {
        return cpu;
    }

    public void setCpu(CpuVO cpu) {
        this.cpu = cpu;
    }

    public MemoryVO getMemory() {
        return memory;
    }

    public void setMemory(MemoryVO memory) {
        this.memory = memory;
    }

    public DiskSpaceVO getDisk() {
        return disk;
    }

    public void setDisk(DiskSpaceVO disk) {
        this.disk = disk;
    }

    public List<MetricResponseVO> getMetrics() {
        return metrics;
    }

    public void setMetrics(List<MetricResponseVO> metrics) {
        this.metrics = metrics;
    }
}
