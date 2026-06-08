package com.ruoyi.sensor.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.sensor.domain.vo.VibrationAnalysisResultVo;
import com.ruoyi.sensor.service.VibrationAnalysisService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sensor/vibration/analysis")
public class VibrationAnalysisController
{
    private final VibrationAnalysisService vibrationAnalysisService;

    public VibrationAnalysisController(VibrationAnalysisService vibrationAnalysisService)
    {
        this.vibrationAnalysisService = vibrationAnalysisService;
    }

    @PostMapping("/analyze")
    public AjaxResult analyze(@RequestBody Map<String, Object> params)
    {
        Object signalObj = params.get("signal");
        Object sampleRateObj = params.get("sampleRate");
        Object deviceCodeObj = params.get("deviceCode");
        Object batchIdObj = params.get("batchId");

        if (!(signalObj instanceof List) || sampleRateObj == null)
        {
            return AjaxResult.error("参数不合法");
        }

        List<?> signalList = (List<?>) signalObj;
        double[] signal = new double[signalList.size()];
        for (int i = 0; i < signalList.size(); i++)
        {
            signal[i] = Double.parseDouble(signalList.get(i).toString());
        }

        double sampleRate = Double.parseDouble(sampleRateObj.toString());
        String deviceCode = deviceCodeObj == null ? null : deviceCodeObj.toString();
        Long batchId = batchIdObj == null ? null : Long.valueOf(batchIdObj.toString());

        VibrationAnalysisResultVo result = vibrationAnalysisService.analyze(signal, sampleRate, deviceCode, batchId);
        return AjaxResult.success(result);
    }

    @PostMapping("/batchAnalyze")
    public AjaxResult batchAnalyze(@RequestBody Map<String, Object> params)
    {
        Object batchObj = params.get("batchSignals");
        Object sampleRateObj = params.get("sampleRate");

        if (!(batchObj instanceof List) || sampleRateObj == null)
        {
            return AjaxResult.error("参数不合法");
        }

        List<?> batchList = (List<?>) batchObj;
        List<double[]> batchSignals = new ArrayList<>();
        for (Object item : batchList)
        {
            if (item instanceof List)
            {
                List<?> signalList = (List<?>) item;
                double[] signal = new double[signalList.size()];
                for (int i = 0; i < signalList.size(); i++)
                {
                    signal[i] = Double.parseDouble(signalList.get(i).toString());
                }
                batchSignals.add(signal);
            }
        }

        double sampleRate = Double.parseDouble(sampleRateObj.toString());
        List<VibrationAnalysisResultVo> result = vibrationAnalysisService.analyzeBatch(batchSignals, sampleRate);
        return AjaxResult.success(result);
    }
}
