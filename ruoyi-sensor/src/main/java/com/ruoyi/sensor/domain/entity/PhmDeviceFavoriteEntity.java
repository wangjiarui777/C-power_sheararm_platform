package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_device_favorite")
public class PhmDeviceFavoriteEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long deviceId;
    private String userName;
    private Date createTime;
}
