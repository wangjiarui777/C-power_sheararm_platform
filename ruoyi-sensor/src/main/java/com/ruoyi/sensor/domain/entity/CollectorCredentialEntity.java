package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sensor_collector_credential")
public class CollectorCredentialEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String collectorId;
    private String collectorName;
    private String encryptedSecret;
    private String secretHash;
    private String allowedDevices;
    private Boolean enabled;
    private Date expireTime;
    private Date lastOnlineTime;
    private String lastIp;
    private String createdBy;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
