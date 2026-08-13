package com.ruoyi.sensor.service;

public class DiagnosisSyncQueuedEvent
{
    private final Long recordId;

    public DiagnosisSyncQueuedEvent(Long recordId)
    {
        this.recordId = recordId;
    }

    public Long getRecordId()
    {
        return recordId;
    }
}
