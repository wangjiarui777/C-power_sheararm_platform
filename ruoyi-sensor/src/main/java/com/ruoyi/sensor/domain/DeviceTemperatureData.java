package com.ruoyi.sensor.domain;

import java.math.BigDecimal;
import java.util.Date;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.annotation.Excel.ColumnType;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * Temperature data record.
 */
public class DeviceTemperatureData extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    @Excel(name = "dataId", cellType = ColumnType.NUMERIC)
    private Long dataId;

    @Excel(name = "deviceCode")
    private String deviceCode;

    private Long pointId;

    private Integer channelId;

    @Excel(name = "temperatureValue")
    private BigDecimal temperatureValue;

    @Excel(name = "collectionTime", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date collectionTime;

    private String quality;

    private Date receiveTime;

    public Long getDataId() { return dataId; }
    public void setDataId(Long dataId) { this.dataId = dataId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Long getPointId() { return pointId; }
    public void setPointId(Long pointId) { this.pointId = pointId; }
    public Integer getChannelId() { return channelId; }
    public void setChannelId(Integer channelId) { this.channelId = channelId; }
    public BigDecimal getTemperatureValue() { return temperatureValue; }
    public void setTemperatureValue(BigDecimal temperatureValue) { this.temperatureValue = temperatureValue; }
    public Date getCollectionTime() { return collectionTime; }
    public void setCollectionTime(Date collectionTime) { this.collectionTime = collectionTime; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    public Date getReceiveTime() { return receiveTime; }
    public void setReceiveTime(Date receiveTime) { this.receiveTime = receiveTime; }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("dataId", getDataId())
            .append("deviceCode", getDeviceCode())
            .append("pointId", getPointId())
            .append("channelId", getChannelId())
            .append("temperatureValue", getTemperatureValue())
            .append("collectionTime", getCollectionTime())
            .append("quality", getQuality())
            .append("receiveTime", getReceiveTime())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}

