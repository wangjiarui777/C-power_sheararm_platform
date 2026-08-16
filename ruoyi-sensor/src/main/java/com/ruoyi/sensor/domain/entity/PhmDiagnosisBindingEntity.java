package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** The single automatic MAT diagnosis model configured for a vibration point. */
@Data
@TableName("phm_diagnosis_binding")
public class PhmDiagnosisBindingEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long deviceId;
    private String deviceCode;
    private Long pointId;
    private Long channelId;
    private String modelType;
    private String modelVersion;
    private Boolean enabled;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
