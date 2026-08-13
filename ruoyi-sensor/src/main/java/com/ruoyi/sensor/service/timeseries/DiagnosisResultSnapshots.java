package com.ruoyi.sensor.service.timeseries;

import com.ruoyi.sensor.domain.entity.EnhancedInferenceRecordEntity;
import org.springframework.beans.BeanUtils;

public final class DiagnosisResultSnapshots
{
    private DiagnosisResultSnapshots()
    {
    }

    public static DiagnosisResultSnapshot fromEntity(EnhancedInferenceRecordEntity entity)
    {
        if (entity == null)
        {
            return null;
        }
        DiagnosisResultSnapshot snapshot = new DiagnosisResultSnapshot();
        BeanUtils.copyProperties(entity, snapshot);
        snapshot.setRecordId(entity.getId());
        return snapshot;
    }

    public static EnhancedInferenceRecordEntity toEntity(DiagnosisResultSnapshot snapshot)
    {
        if (snapshot == null)
        {
            return null;
        }
        EnhancedInferenceRecordEntity entity = new EnhancedInferenceRecordEntity();
        BeanUtils.copyProperties(snapshot, entity);
        entity.setId(snapshot.getRecordId());
        return entity;
    }
}
