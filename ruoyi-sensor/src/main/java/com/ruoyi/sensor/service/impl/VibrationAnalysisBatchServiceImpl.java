package com.ruoyi.sensor.service.impl;

import java.util.Arrays;
import java.util.List;

import com.ruoyi.sensor.domain.entity.VibrationAnalysisBatchEntity;
import com.ruoyi.sensor.mapper.VibrationAnalysisBatchMapper;
import com.ruoyi.sensor.service.VibrationAnalysisBatchService;
import org.springframework.stereotype.Service;

@Service
public class VibrationAnalysisBatchServiceImpl implements VibrationAnalysisBatchService
{
    private final VibrationAnalysisBatchMapper mapper;

    public VibrationAnalysisBatchServiceImpl(VibrationAnalysisBatchMapper mapper)
    {
        this.mapper = mapper;
    }

    @Override
    public VibrationAnalysisBatchEntity getById(Long batchId)
    {
        return batchId == null ? null : mapper.selectByBatchId(batchId);
    }

    @Override
    public List<VibrationAnalysisBatchEntity> list(VibrationAnalysisBatchEntity query)
    {
        if (query == null)
        {
            return mapper.selectBatchList(null, null, null);
        }
        return mapper.selectBatchList(query.getBatchId(), query.getDeviceCode(), query.getSampleRate());
    }

    @Override
    public int insert(VibrationAnalysisBatchEntity entity)
    {
        return mapper.insertBatch(entity);
    }

    @Override
    public int update(VibrationAnalysisBatchEntity entity)
    {
        return mapper.updateBatch(entity);
    }

    @Override
    public int deleteByIds(Long[] batchIds)
    {
        if (batchIds == null || batchIds.length == 0)
        {
            return 0;
        }
        return mapper.deleteByBatchIds(Arrays.asList(batchIds));
    }
}
