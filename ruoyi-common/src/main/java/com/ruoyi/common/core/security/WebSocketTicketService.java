package com.ruoyi.common.core.security;

import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.domain.model.WebSocketTicketPrincipal;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.uuid.IdUtils;

/**
 * Issues one-time WebSocket tickets so browser clients do not expose their
 * long-lived bearer token in a WebSocket URL.
 */
@Component
public class WebSocketTicketService
{
    private static final String CACHE_PREFIX = "sensor:ws-ticket:";
    private static final int TICKET_TTL_SECONDS = 60;

    private final RedisCache redisCache;

    public WebSocketTicketService(RedisCache redisCache)
    {
        this.redisCache = redisCache;
    }

    public String issue(LoginUser loginUser)
    {
        if (loginUser == null || StringUtils.isEmpty(loginUser.getUsername()))
        {
            throw new IllegalArgumentException("Authenticated user is required");
        }
        String ticket = IdUtils.fastUUID();
        WebSocketTicketPrincipal principal = new WebSocketTicketPrincipal();
        principal.setUserId(loginUser.getUserId());
        principal.setUsername(loginUser.getUsername());
        principal.setPermissions(loginUser.getPermissions());
        redisCache.setCacheObject(cacheKey(ticket), JSON.toJSONString(principal), TICKET_TTL_SECONDS, TimeUnit.SECONDS);
        return ticket;
    }

    public WebSocketTicketPrincipal peek(String ticket)
    {
        if (StringUtils.isEmpty(ticket))
        {
            return null;
        }
        return deserialize(redisCache.getCacheObject(cacheKey(ticket)));
    }

    public WebSocketTicketPrincipal consume(String ticket)
    {
        if (StringUtils.isEmpty(ticket))
        {
            return null;
        }
        return deserialize(redisCache.getAndDeleteCacheObject(cacheKey(ticket)));
    }

    public int getTicketTtlSeconds()
    {
        return TICKET_TTL_SECONDS;
    }

    private String cacheKey(String ticket)
    {
        return CACHE_PREFIX + ticket;
    }

    private WebSocketTicketPrincipal deserialize(Object cached)
    {
        if (cached == null)
        {
            return null;
        }
        if (cached instanceof WebSocketTicketPrincipal principal)
        {
            return principal;
        }
        return JSON.parseObject(String.valueOf(cached), WebSocketTicketPrincipal.class);
    }
}
