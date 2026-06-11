package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_attachment")
public class PhmAttachmentEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String bizType;
    private Long bizId;
    private String fileName;
    private String fileUrl;
    private String fileExt;
    private String reportType;
    private String uploadBy;
    private Date createTime;
    private String remark;
}
