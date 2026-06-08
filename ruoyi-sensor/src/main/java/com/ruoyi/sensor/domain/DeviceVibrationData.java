package com.ruoyi.sensor.domain;

import java.math.BigDecimal;
import java.util.Date;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.annotation.Excel.ColumnType;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * Vibration data record.
 */
public class DeviceVibrationData extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    @Excel(name = "dataId", cellType = ColumnType.NUMERIC)
    private Long dataId;

    @Excel(name = "deviceCode")
    private String deviceCode;

    @Excel(name = "channelId")
    private Integer channelId;

    @Excel(name = "temperatureValue")
    private BigDecimal temperatureValue;

    @Excel(name = "vibrationValue")
    private BigDecimal vibrationValue;

    @Excel(name = "accelerationValue")
    private BigDecimal accelerationValue;

    @Excel(name = "sampleTime", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date sampleTime;

    public Long getDataId() { return dataId; }
    public void setDataId(Long dataId) { this.dataId = dataId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Integer getChannelId() { return channelId; }
    public void setChannelId(Integer channelId) { this.channelId = channelId; }
    public BigDecimal getTemperatureValue() { return temperatureValue; }
    public void setTemperatureValue(BigDecimal temperatureValue) { this.temperatureValue = temperatureValue; }
    public BigDecimal getVibrationValue() { return vibrationValue; }
    public void setVibrationValue(BigDecimal vibrationValue) { this.vibrationValue = vibrationValue; }
    public BigDecimal getAccelerationValue() { return accelerationValue; }
    public void setAccelerationValue(BigDecimal accelerationValue) { this.accelerationValue = accelerationValue; }
    public Date getSampleTime() { return sampleTime; }
    public void setSampleTime(Date sampleTime) { this.sampleTime = sampleTime; }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("dataId", getDataId())
            .append("deviceCode", getDeviceCode())
            .append("channelId", getChannelId())
            .append("temperatureValue", getTemperatureValue())
            .append("vibrationValue", getVibrationValue())
            .append("accelerationValue", getAccelerationValue())
            .append("sampleTime", getSampleTime())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}

