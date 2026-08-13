package com.ruoyi.web.controller.common;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.service.PhmAttachmentStorageService;

/** Authorized generic attachment API. */
@RestController
@RequestMapping("/attachments")
public class AttachmentController
{
    private final PhmAttachmentStorageService attachmentStorageService;

    public AttachmentController(PhmAttachmentStorageService attachmentStorageService)
    {
        this.attachmentStorageService = attachmentStorageService;
    }

    @PostMapping
    public AjaxResult upload(@RequestParam("file") MultipartFile file) throws Exception
    {
        PhmAttachmentEntity entity = attachmentStorageService.storeGeneric(file, SecurityUtils.getUsername());
        return AjaxResult.success(java.util.Map.of(
            "attachmentId", entity.getId(),
            "contentUrl", "/attachments/" + entity.getId() + "/content",
            "fileName", entity.getFileName(),
            "sha256", entity.getSha256()));
    }

    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<FileSystemResource> content(@PathVariable Long attachmentId) throws Exception
    {
        PhmAttachmentEntity entity = attachmentStorageService.getAccessibleGeneric(attachmentId);
        if (entity == null)
        {
            return ResponseEntity.notFound().build();
        }
        FileSystemResource resource = attachmentStorageService.content(entity);
        boolean inlineImage = "GENERIC_IMAGE".equals(entity.getPurpose());
        ContentDisposition disposition = (inlineImage ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(entity.getFileName(), StandardCharsets.UTF_8)
            .build();
        MediaType mediaType = inlineImage ? MediaType.parseMediaType(entity.getMimeType())
            : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .contentType(mediaType)
            .contentLength(resource.contentLength())
            .body(resource);
    }

    @DeleteMapping("/{attachmentId}")
    public AjaxResult delete(@PathVariable Long attachmentId) throws Exception
    {
        return attachmentStorageService.deleteGeneric(attachmentId) > 0
            ? AjaxResult.success() : AjaxResult.error("附件不存在或无权删除");
    }
}
