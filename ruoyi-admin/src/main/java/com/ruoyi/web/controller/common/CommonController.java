package com.ruoyi.web.controller.common;

import java.util.ArrayList;
import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.service.PhmAttachmentStorageService;

/**
 * Compatibility endpoints for generated exports and legacy upload components.
 * Uploaded content is always stored through the authorized attachment service;
 * no physical profile path is returned to the browser.
 */
@RestController
@RequestMapping("/common")
public class CommonController
{
    private static final String FILE_DELIMITER = ",";

    private final PhmAttachmentStorageService attachmentStorageService;

    public CommonController(PhmAttachmentStorageService attachmentStorageService)
    {
        this.attachmentStorageService = attachmentStorageService;
    }

    /**
     * Downloads a server-generated temporary export. The obsolete client
     * controlled delete flag and all deletion side effects are intentionally
     * absent from this GET endpoint; scheduled retention handles cleanup.
     */
    @GetMapping("/download")
    public void fileDownload(String fileName, HttpServletResponse response) throws Exception
    {
        if (!FileUtils.checkAllowDownload(fileName))
        {
            throw new IllegalArgumentException(StringUtils.format("文件名({})非法，不允许下载", fileName));
        }
        String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
        String filePath = RuoYiConfig.getDownloadPath() + fileName;
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("X-Content-Type-Options", "nosniff");
        FileUtils.setAttachmentResponseHeader(response, realFileName);
        FileUtils.writeBytes(filePath, response.getOutputStream());
    }

    /** Legacy upload compatibility proxy. New clients should use POST /attachments. */
    @PostMapping("/upload")
    public AjaxResult uploadFile(MultipartFile file)
    {
        try
        {
            return attachmentResult(attachmentStorageService.storeGeneric(file, SecurityUtils.getUsername()));
        }
        catch (Exception ex)
        {
            return AjaxResult.error(ex.getMessage());
        }
    }

    /** Legacy batch upload compatibility proxy. */
    @PostMapping("/uploads")
    public AjaxResult uploadFiles(List<MultipartFile> files)
    {
        try
        {
            List<String> urls = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (MultipartFile file : files)
            {
                PhmAttachmentEntity entity = attachmentStorageService.storeGeneric(file, SecurityUtils.getUsername());
                urls.add(contentUrl(entity));
                names.add(entity.getFileName());
                ids.add(String.valueOf(entity.getId()));
            }
            AjaxResult result = AjaxResult.success();
            result.put("urls", StringUtils.join(urls, FILE_DELIMITER));
            result.put("fileNames", StringUtils.join(urls, FILE_DELIMITER));
            result.put("newFileNames", StringUtils.join(names, FILE_DELIMITER));
            result.put("originalFilenames", StringUtils.join(names, FILE_DELIMITER));
            result.put("attachmentIds", ids);
            return result;
        }
        catch (Exception ex)
        {
            return AjaxResult.error(ex.getMessage());
        }
    }

    private AjaxResult attachmentResult(PhmAttachmentEntity entity)
    {
        String url = contentUrl(entity);
        AjaxResult result = AjaxResult.success();
        result.put("attachmentId", entity.getId());
        result.put("url", url);
        result.put("fileName", url);
        result.put("newFileName", entity.getFileName());
        result.put("originalFilename", entity.getFileName());
        result.put("sha256", entity.getSha256());
        return result;
    }

    private String contentUrl(PhmAttachmentEntity entity)
    {
        return "/attachments/" + entity.getId() + "/content";
    }
}
