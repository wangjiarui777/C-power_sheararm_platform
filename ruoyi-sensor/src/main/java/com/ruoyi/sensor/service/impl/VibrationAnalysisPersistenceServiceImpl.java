package com.ruoyi.sensor.service.impl;

import java.util.ArrayList;
import java.util.List;

import com.ruoyi.sensor.domain.entity.VibrationAnalysisRecordEntity;
import com.ruoyi.sensor.mapper.VibrationAnalysisRecordMapper;
import com.ruoyi.sensor.service.VibrationAnalysisPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VibrationAnalysisPersistenceServiceImpl implements VibrationAnalysisPersistenceService
{
    private static final Logger log = LoggerFactory.getLogger(VibrationAnalysisPersistenceServiceImpl.class);

    private final VibrationAnalysisRecordMapper recordMapper;

    public VibrationAnalysisPersistenceServiceImpl(VibrationAnalysisRecordMapper recordMapper)
    {
        this.recordMapper = recordMapper;
    }

    @Override
    @Async("vibrationExecutor")
    public void saveAsync(VibrationAnalysisRecordEntity record)
    {
        if (record == null)
        {
            return;
        }
        try
        {
            recordMapper.insert(record);
        }
        catch (DataAccessException e)
        {
            log.error("�첽�����񶯷������ʧ��, batchId={}, deviceCode={}", record.getBatchId(), record.getDeviceCode(), e);
        }
        catch (Exception e)
        {
            log.error("�첽�����񶯷����������δ֪�쳣, batchId={}, deviceCode={}", record.getBatchId(), record.getDeviceCode(), e);
        }
    }

    @Override
    @Async("vibrationExecutor")
    @Transactional(rollbackFor = Exception.class)
    public void saveBatchAsync(List<VibrationAnalysisRecordEntity> records)
    {
        if (records == null || records.isEmpty())
        {
            return;
        }

        final int batchSize = 50;
        List<VibrationAnalysisRecordEntity> buffer = new ArrayList<>(batchSize);
        try
        {
            for (VibrationAnalysisRecordEntity record : records)
            {
                if (record == null)
                {
                    continue;
                }
                buffer.add(record);
                if (buffer.size() >= batchSize)
                {
                    flushBuffer(buffer);
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty())
            {
                flushBuffer(buffer);
            }
        }
        catch (DataAccessException e)
        {
            log.error("���������񶯷������ʧ��, size={}", records.size(), e);
            throw e;
        }
        catch (Exception e)
        {
            log.error("���������񶯷����������δ֪�쳣, size={}", records.size(), e);
            throw new RuntimeException(e);
        }
    }

    private void flushBuffer(List<VibrationAnalysisRecordEntity> buffer)
    {
        for (VibrationAnalysisRecordEntity record : buffer)
        {
            recordMapper.insert(record);
        }
        log.info("���������񶯷�������ɹ�, batchSize={}", buffer.size());
    }
}
