package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_system_config")
public class PhmSystemConfigEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String configKey;
    private String configValue;
    private String configName;
    private String configType;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
