package com.ruoyi.sensor.web;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.sensor.domain.entity.ModelReleaseEntity;
import com.ruoyi.sensor.mapper.ModelReleaseMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sensor/diagnosis/models")
public class ModelReleaseController extends BaseController
{
    private final ModelReleaseMapper mapper;

    public ModelReleaseController(ModelReleaseMapper mapper)
    {
        this.mapper = mapper;
    }

    @PreAuthorize("@ss.hasPermi('phm:config:list')")
    @GetMapping
    public AjaxResult list()
    {
        List<ModelReleaseEntity> rows = mapper.selectList(
            new LambdaQueryWrapper<ModelReleaseEntity>().orderByDesc(ModelReleaseEntity::getCreateTime));
        return success(rows);
    }

    @PreAuthorize("@ss.hasPermi('phm:config:edit')")
    @Log(title = "模型发布登记", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody ModelReleaseEntity release)
    {
        String error = validateMetadata(release, false);
        if (error != null)
        {
            return error(error);
        }
        Date now = new Date();
        release.setId(null);
        release.setStatus("DRAFT");
        release.setCreatedBy(SecurityUtils.getUsername());
        release.setCreateTime(now);
        release.setUpdateTime(now);
        return toAjax(mapper.insert(release));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:edit')")
    @Log(title = "激活模型版本", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/activate")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult activate(@PathVariable Long id)
    {
        ModelReleaseEntity release = mapper.selectById(id);
        if (release == null)
        {
            return error("模型发布记录不存在");
        }
        String validationError = validateMetadata(release, true);
        if (validationError != null)
        {
            return error(validationError);
        }
        List<ModelReleaseEntity> active = mapper.selectList(
            new LambdaQueryWrapper<ModelReleaseEntity>()
                .eq(ModelReleaseEntity::getModelType, release.getModelType())
                .eq(ModelReleaseEntity::getStatus, "ACTIVE"));
        Date now = new Date();
        for (ModelReleaseEntity item : active)
        {
            item.setStatus("RETIRED");
            item.setUpdateTime(now);
            mapper.updateById(item);
        }
        release.setStatus("ACTIVE");
        release.setActivatedBy(SecurityUtils.getUsername());
        release.setActivateTime(now);
        release.setUpdateTime(now);
        mapper.updateById(release);
        return success(release);
    }

    private String validateMetadata(ModelReleaseEntity release, boolean activation)
    {
        if (release == null || isBlank(release.getModelName()) || isBlank(release.getModelType())
            || isBlank(release.getSemanticVersion()) || isBlank(release.getFileSha256())
            || isBlank(release.getTrainingDataVersion()) || isBlank(release.getValidationDataVersion())
            || isBlank(release.getThresholdVersion()))
        {
            return "模型名称、类型、语义版本、SHA-256、数据版本和阈值版本均为必填项";
        }
        if (!release.getFileSha256().matches("(?i)[0-9a-f]{64}"))
        {
            return "模型文件 SHA-256 格式错误";
        }
        if (!activation)
        {
            return null;
        }
        if (below(release.getPrecisionScore(), "0.90") || below(release.getRecallScore(), "0.90"))
        {
            return "主要故障类别 precision 和 recall 必须均不低于 90%";
        }
        if (below(release.getSevereRecallScore(), "0.95"))
        {
            return "严重故障 recall 必须不低于 95%";
        }
        if (release.getFalsePositivePerDeviceDay() == null
            || release.getFalsePositivePerDeviceDay().compareTo(BigDecimal.ONE) > 0)
        {
            return "正常工况误报率必须不高于每台设备每天 1 次";
        }
        if (release.getShadowDays() == null || release.getShadowDays() < 14)
        {
            return "模型必须完成至少 14 天影子运行";
        }
        if (release.getConfidenceThreshold() == null
            || release.getConfidenceThreshold().compareTo(BigDecimal.ZERO) < 0
            || release.getConfidenceThreshold().compareTo(new BigDecimal("100")) > 0)
        {
            return "置信度阈值必须在 0 到 100 之间";
        }
        if (release.getConsecutiveHits() == null || release.getConsecutiveHits() < 1)
        {
            return "连续命中次数必须大于 0";
        }
        return null;
    }

    private boolean below(BigDecimal value, String threshold)
    {
        return value == null || value.compareTo(new BigDecimal(threshold)) < 0;
    }

    private boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }
}
