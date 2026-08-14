package com.ruoyi.generator.controller;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.generator.lowcode.LowCodeConnectorService;

@RestController
@RequestMapping("/tool/lowcode/connectors")
public class LowCodeConnectorController extends BaseController
{
    private final LowCodeConnectorService connectors;
    public LowCodeConnectorController(LowCodeConnectorService connectors) { this.connectors = connectors; }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:connector')")
    @GetMapping public AjaxResult list() { return success(connectors.list()); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:connector')")
    @Log(title = "低代码连接器", businessType = BusinessType.UPDATE)
    @PutMapping public AjaxResult save(@RequestBody Map<String, Object> input) { return success(connectors.save(input)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:connector')")
    @PostMapping("/{code}/test") public AjaxResult test(@PathVariable String code) { return success(connectors.test(code)); }
}
