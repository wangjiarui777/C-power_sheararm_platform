package com.ruoyi.system.domain;

/**
 * Request/response model for replacing one user's device authorization.
 */
public class SysUserDeviceAuth
{
    private Long userId;
    private Long[] deviceIds;

    public Long getUserId()
    {
        return userId;
    }

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public Long[] getDeviceIds()
    {
        return deviceIds;
    }

    public void setDeviceIds(Long[] deviceIds)
    {
        this.deviceIds = deviceIds;
    }
}
