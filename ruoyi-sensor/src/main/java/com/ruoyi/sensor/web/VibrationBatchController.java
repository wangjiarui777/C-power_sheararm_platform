package com.ruoyi.sensor.web;

import java.util.List;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.sensor.domain.entity.VibrationAnalysisBatchEntity;
import com.ruoyi.sensor.service.VibrationAnalysisBatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sensor/vibration/batch")
public class VibrationBatchController
{
    private final VibrationAnalysisBatchService batchService;

    public VibrationBatchController(VibrationAnalysisBatchService batchService)
    {
        this.batchService = batchService;
    }

    @GetMapping("/list")
    public AjaxResult list(VibrationAnalysisBatchEntity query)
    {
        List<VibrationAnalysisBatchEntity> list = batchService.list(query);
        return AjaxResult.success(list);
    }

    @GetMapping("/detail/{batchId}")
    public AjaxResult detail(@PathVariable Long batchId)
    {
        return AjaxResult.success(batchService.getById(batchId));
    }

    @GetMapping("/page")
    public AjaxResult page(VibrationAnalysisBatchEntity query,
                           @RequestParam(defaultValue = "1") int pageNum,
                           @RequestParam(defaultValue = "10") int pageSize)
    {
        List<VibrationAnalysisBatchEntity> all = batchService.list(query);
        int fromIndex = Math.max(0, (pageNum - 1) * pageSize);
        int toIndex = Math.min(all.size(), fromIndex + pageSize);
        List<VibrationAnalysisBatchEntity> page = fromIndex >= all.size() ? java.util.Collections.emptyList() : all.subList(fromIndex, toIndex);

        return AjaxResult.success(new PageResult(all.size(), pageNum, pageSize, page));
    }

    @PostMapping
    public AjaxResult add(@RequestBody VibrationAnalysisBatchEntity entity)
    {
        return AjaxResult.success(batchService.insert(entity));
    }

    @PutMapping
    public AjaxResult edit(@RequestBody VibrationAnalysisBatchEntity entity)
    {
        return AjaxResult.success(batchService.update(entity));
    }

    @DeleteMapping("/{batchIds}")
    public AjaxResult remove(@PathVariable Long[] batchIds)
    {
        return AjaxResult.success(batchService.deleteByIds(batchIds));
    }

    public static class PageResult
    {
        private final long total;
        private final int pageNum;
        private final int pageSize;
        private final List<VibrationAnalysisBatchEntity> rows;

        public PageResult(long total, int pageNum, int pageSize, List<VibrationAnalysisBatchEntity> rows)
        {
            this.total = total;
            this.pageNum = pageNum;
            this.pageSize = pageSize;
            this.rows = rows;
        }

        public long getTotal() { return total; }
        public int getPageNum() { return pageNum; }
        public int getPageSize() { return pageSize; }
        public List<VibrationAnalysisBatchEntity> getRows() { return rows; }
    }
}
