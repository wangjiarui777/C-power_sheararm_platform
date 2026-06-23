package com.ruoyi.sensor.web;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.CollectorSecretCrypto;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.sensor.domain.entity.CollectorCredentialEntity;
import com.ruoyi.sensor.mapper.CollectorCredentialMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sensor/collectors")
public class CollectorCredentialController extends BaseController
{
    private final CollectorCredentialMapper mapper;
    private final String masterKey;

    public CollectorCredentialController(CollectorCredentialMapper mapper,
        @Value("${sensor.collector.master-key:}") String masterKey)
    {
        this.mapper = mapper;
        this.masterKey = masterKey;
    }

    @PreAuthorize("@ss.hasPermi('phm:config:list')")
    @GetMapping
    public AjaxResult list()
    {
        List<CollectorCredentialEntity> rows = mapper.selectList(
            new LambdaQueryWrapper<CollectorCredentialEntity>()
                .orderByAsc(CollectorCredentialEntity::getCollectorId));
        rows.forEach(item -> item.setEncryptedSecret(null));
        return success(rows);
    }

    @PreAuthorize("@ss.hasPermi('phm:config:add')")
    @Log(title = "采集凭据创建", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody CollectorCredentialEntity request)
    {
        if (request.getCollectorId() == null || !request.getCollectorId().matches("[A-Za-z0-9_-]{3,64}"))
        {
            return error("collectorId 格式错误");
        }
        if (request.getAllowedDevices() == null || request.getAllowedDevices().isBlank())
        {
            return error("allowedDevices 为必填项，可填写逗号分隔设备编码或 *");
        }
        String secret = CollectorSecretCrypto.generateSecret();
        Date now = new Date();
        request.setId(null);
        request.setEncryptedSecret(CollectorSecretCrypto.encrypt(secret, masterKey));
        request.setSecretHash(CollectorSecretCrypto.sha256Hex(secret.getBytes(StandardCharsets.UTF_8)));
        request.setEnabled(true);
        request.setCreatedBy(SecurityUtils.getUsername());
        request.setCreateTime(now);
        request.setUpdateTime(now);
        mapper.insert(request);
        return success(secretResult(request, secret));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:edit')")
    @Log(title = "采集凭据轮换", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/rotate")
    public AjaxResult rotate(@PathVariable Long id)
    {
        CollectorCredentialEntity entity = mapper.selectById(id);
        if (entity == null)
        {
            return error("采集凭据不存在");
        }
        String secret = CollectorSecretCrypto.generateSecret();
        entity.setEncryptedSecret(CollectorSecretCrypto.encrypt(secret, masterKey));
        entity.setSecretHash(CollectorSecretCrypto.sha256Hex(secret.getBytes(StandardCharsets.UTF_8)));
        entity.setUpdateTime(new Date());
        mapper.updateById(entity);
        return success(secretResult(entity, secret));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:edit')")
    @Log(title = "采集凭据禁用", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/disable")
    public AjaxResult disable(@PathVariable Long id)
    {
        CollectorCredentialEntity entity = mapper.selectById(id);
        if (entity == null)
        {
            return error("采集凭据不存在");
        }
        entity.setEnabled(false);
        entity.setUpdateTime(new Date());
        return toAjax(mapper.updateById(entity));
    }

    private Map<String, Object> secretResult(CollectorCredentialEntity entity, String secret)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("collectorId", entity.getCollectorId());
        result.put("secret", secret);
        result.put("notice", "该密钥只显示一次，请立即安全保存");
        return result;
    }
}
