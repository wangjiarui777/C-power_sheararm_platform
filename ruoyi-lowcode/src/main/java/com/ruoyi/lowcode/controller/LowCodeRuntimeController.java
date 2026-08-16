package com.ruoyi.lowcode.controller;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.lowcode.core.LowCodeRuntimeService;

@RestController
@RequestMapping("/lowcode/runtime/{appCode}")
public class LowCodeRuntimeController extends BaseController
{
    private final LowCodeRuntimeService runtime;
    public LowCodeRuntimeController(LowCodeRuntimeService runtime) { this.runtime = runtime; }

    @PreAuthorize("@ss.hasPermi('lowcode:runtime:query')")
    @GetMapping("/schema") public AjaxResult schema(@PathVariable String appCode) { return success(runtime.schema(appCode)); }

    @PreAuthorize("@ss.hasPermi('lowcode:runtime:query')")
    @GetMapping("/records") public AjaxResult list(@PathVariable String appCode, @RequestParam Map<String, String> query)
    { return success(runtime.list(appCode, query)); }

    @PreAuthorize("@ss.hasPermi('lowcode:runtime:query')")
    @GetMapping("/records/{id}") public AjaxResult get(@PathVariable String appCode, @PathVariable String id)
    { return success(runtime.get(appCode, id)); }

    @PreAuthorize("@ss.hasPermi('lowcode:runtime:add')")
    @PostMapping("/records") public AjaxResult create(@PathVariable String appCode, @RequestBody Map<String, Object> input)
    { return success(runtime.create(appCode, input)); }

    @PreAuthorize("@ss.hasPermi('lowcode:runtime:edit')")
    @PutMapping("/records/{id}") public AjaxResult update(@PathVariable String appCode, @PathVariable String id,
        @RequestBody Map<String, Object> input) { return success(runtime.update(appCode, id, input)); }

    @PreAuthorize("@ss.hasPermi('lowcode:runtime:remove')")
    @DeleteMapping("/records/{id}") public AjaxResult delete(@PathVariable String appCode, @PathVariable String id)
    { runtime.delete(appCode, id); return success(); }

    @PreAuthorize("@ss.hasPermi('lowcode:runtime:action')")
    @PostMapping("/actions/{actionCode}")
    public AjaxResult action(@PathVariable String appCode, @PathVariable String actionCode,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody Map<String, Object> input)
    { return success(runtime.action(appCode, actionCode, input, idempotencyKey)); }
}
