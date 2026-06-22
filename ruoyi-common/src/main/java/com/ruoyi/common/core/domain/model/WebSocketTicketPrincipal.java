package com.ruoyi.common.core.domain.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Short-lived identity snapshot used during a WebSocket handshake.
 */
public class WebSocketTicketPrincipal implements Serializable
{
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private Set<String> permissions = new HashSet<>();

    public Long getUserId()
    {
        return userId;
    }

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public String getUsername()
    {
        return username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public Set<String> getPermissions()
    {
        return permissions == null ? Collections.emptySet() : permissions;
    }

    public void setPermissions(Set<String> permissions)
    {
        this.permissions = permissions == null ? new HashSet<>() : new HashSet<>(permissions);
    }
}
