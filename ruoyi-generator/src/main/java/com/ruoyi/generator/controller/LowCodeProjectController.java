package com.ruoyi.generator.controller;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
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
import com.ruoyi.generator.lowcode.LowCodeProjectService;
import com.ruoyi.generator.lowcode.LowCodeToolingService;
import com.ruoyi.generator.lowcode.LowCodeTablePolicy;
import com.ruoyi.generator.lowcode.LowCodePipelineService;

@RestController
@RequestMapping("/tool/lowcode/projects")
public class LowCodeProjectController extends BaseController
{
    private final LowCodeProjectService projects;
    private final LowCodeToolingService tooling;
    private final LowCodeTablePolicy tablePolicy;
    private final LowCodePipelineService pipelines;

    public LowCodeProjectController(LowCodeProjectService projects, LowCodeToolingService tooling, LowCodeTablePolicy tablePolicy, LowCodePipelineService pipelines)
    { this.projects = projects; this.tooling = tooling; this.tablePolicy = tablePolicy; this.pipelines = pipelines; }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @GetMapping("/{id}/pipeline/status") public AjaxResult pipelineStatus(@PathVariable Long id) { return success(pipelines.status(id)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @GetMapping("/{id}/pipeline/runs") public AjaxResult pipelineRuns(@PathVariable Long id) { return success(pipelines.runs(id, 50)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:test')")
    @Log(title = "工业诊断管道试运行", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/pipeline/test") public AjaxResult pipelineTest(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> request) { return success(pipelines.test(id, request == null ? Map.of() : request)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:activate')")
    @Log(title = "工业诊断管道启用", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/pipeline/activate") public AjaxResult pipelineActivate(@PathVariable Long id) { return success(pipelines.activate(id)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:activate')")
    @Log(title = "工业诊断管道停用", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/pipeline/deactivate") public AjaxResult pipelineDeactivate(@PathVariable Long id) { return success(pipelines.deactivate(id)); }

@PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @GetMapping public AjaxResult list() { return success(projects.list()); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @GetMapping("/{id}") public AjaxResult get(@PathVariable Long id) { return success(projects.get(id)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @Log(title = "低代码项目", businessType = BusinessType.INSERT)
    @PostMapping public AjaxResult create(@RequestBody Map<String, Object> request) { return success(projects.create(request)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @Log(title = "低代码草稿", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/draft")
    public AjaxResult save(@PathVariable Long id, @RequestBody Object metadata) { return success(projects.saveDraft(id, metadata)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:validate')")
    @PostMapping("/{id}/validate") public AjaxResult validate(@PathVariable Long id) { return success(projects.validate(id)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @GetMapping("/{id}/diff") public AjaxResult diff(@PathVariable Long id) { return success(projects.diff(id)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:publish')")
    @Log(title = "低代码发布", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/publish") public AjaxResult publish(@PathVariable Long id) { return success(projects.publish(id)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:rollback')")
    @Log(title = "低代码回滚", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/rollback/{versionId}")
    public AjaxResult rollback(@PathVariable Long id, @PathVariable Long versionId) { return success(projects.rollback(id, versionId)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @GetMapping("/{id}/database/inspect") public AjaxResult inspect(@PathVariable Long id)
    { projects.get(id); return success(tooling.inspect()); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @PostMapping("/{id}/database/ddl-preview") public AjaxResult ddl(@PathVariable Long id, @RequestBody Object metadata)
    { projects.get(id); return success(tooling.ddlPreview(metadata)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @PostMapping("/migrate/{tableId}") public AjaxResult migrate(@PathVariable Long tableId)
    { return success(tooling.migrateLegacy(tableId)); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:connector')")
    @GetMapping("/resource-allowlist") public AjaxResult resources() { return success(tablePolicy.list()); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:connector')")
    @PostMapping("/resource-allowlist") public AjaxResult allowResource(@RequestBody Map<String, Object> input)
    {
        tablePolicy.register(String.valueOf(input.get("tableName")),
                input.get("description") == null ? null : String.valueOf(input.get("description")), getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:connector')")
    @PostMapping("/resource-allowlist/{id}/disable") public AjaxResult disableResource(@PathVariable Long id)
    { tablePolicy.disable(id); return success(); }

    @PreAuthorize("@ss.hasPermi('tool:lowcode:design')")
    @GetMapping("/{id}/export")
    public void export(@PathVariable Long id, HttpServletResponse response) throws IOException
    {
        response.reset(); response.setContentType("application/zip");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Disposition", "attachment; filename=lowcode-v2.zip");
        tooling.export(id, response.getOutputStream());
    }
}
