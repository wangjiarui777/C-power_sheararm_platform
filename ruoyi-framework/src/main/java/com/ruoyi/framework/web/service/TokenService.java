package com.ruoyi.framework.web.service;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.http.UserAgentUtils;
import com.ruoyi.common.utils.ip.AddressUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.common.utils.uuid.IdUtils;

/** Redis-backed opaque browser session service. */
@Component
public class TokenService
{
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final long MILLIS_MINUTE = 60_000L;
    private static final long REFRESH_THRESHOLD = 20 * MILLIS_MINUTE;

    @Value("${token.expireTime:30}")
    private int expireTime;

    @Value("${security.session.cookie-name:RUOYI_SESSION}")
    private String cookieName;

    @Value("${security.session.secure-cookie:true}")
    private boolean secureCookie;

    @Autowired
    private RedisCache redisCache;

    public LoginUser getLoginUser(HttpServletRequest request)
    {
        String sessionId = getSessionId(request);
        if (StringUtils.isEmpty(sessionId)) return null;
        try
        {
            return redisCache.getCacheObject(getTokenKey(sessionId));
        }
        catch (RuntimeException ex)
        {
            log.warn("Unable to read browser session: {}", ex.getMessage());
            return null;
        }
    }

    public void setLoginUser(LoginUser loginUser)
    {
        if (loginUser != null && StringUtils.isNotEmpty(loginUser.getToken())) refreshToken(loginUser);
    }

    public void delLoginUser(String sessionId)
    {
        if (StringUtils.isNotEmpty(sessionId)) redisCache.deleteObject(getTokenKey(sessionId));
    }

    public String createToken(LoginUser loginUser)
    {
        String sessionId = IdUtils.fastUUID();
        loginUser.setToken(sessionId);
        setUserAgent(loginUser);
        refreshToken(loginUser);
        return sessionId;
    }

    public void verifyToken(LoginUser loginUser)
    {
        if (loginUser.getExpireTime() - System.currentTimeMillis() <= REFRESH_THRESHOLD) refreshToken(loginUser);
    }

    public void refreshToken(LoginUser loginUser)
    {
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setExpireTime(loginUser.getLoginTime() + expireTime * MILLIS_MINUTE);
        redisCache.setCacheObject(getTokenKey(loginUser.getToken()), loginUser, expireTime, TimeUnit.MINUTES);
    }

    public void setUserAgent(LoginUser loginUser)
    {
        String userAgent = ServletUtils.getRequest().getHeader("User-Agent");
        String ip = IpUtils.getIpAddr();
        loginUser.setIpaddr(ip);
        loginUser.setLoginLocation(AddressUtils.getRealAddressByIP(ip));
        loginUser.setBrowser(UserAgentUtils.getBrowser(userAgent));
        loginUser.setOs(UserAgentUtils.getOperatingSystem(userAgent));
    }

    public void writeSessionCookie(HttpServletResponse response, String sessionId)
    {
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(sessionId, expireTime * 60L).toString());
    }

    public void clearSessionCookie(HttpServletResponse response)
    {
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString());
    }

    public void revokeUserSessions(Long userId)
    {
        Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        if (keys == null) return;
        for (String key : keys)
        {
            LoginUser user = redisCache.getCacheObject(key);
            if (user != null && userId.equals(user.getUserId())) redisCache.deleteObject(key);
        }
    }

    public void revokeUserSessions(Collection<Long> userIds)
    {
        if (userIds == null || userIds.isEmpty()) return;
        Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        if (keys == null) return;
        for (String key : keys)
        {
            LoginUser user = redisCache.getCacheObject(key);
            if (user != null && userIds.contains(user.getUserId())) redisCache.deleteObject(key);
        }
    }

    public void revokeRoleSessions(Long roleId)
    {
        Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        if (keys == null) return;
        for (String key : keys)
        {
            LoginUser user = redisCache.getCacheObject(key);
            boolean assigned = user != null && user.getUser() != null && user.getUser().getRoles() != null
                    && user.getUser().getRoles().stream().anyMatch(role -> roleId.equals(role.getRoleId()));
            if (assigned) redisCache.deleteObject(key);
        }
    }

    public void refreshPermissionByRoleId(Long roleId, SysPermissionService permissionService)
    {
        Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        if (keys == null) return;
        for (String key : keys)
        {
            LoginUser user = redisCache.getCacheObject(key);
            if (user == null || user.getUser() == null || user.getUser().isAdmin()) continue;
            boolean assigned = user.getUser().getRoles() != null
                    && user.getUser().getRoles().stream().anyMatch(role -> roleId.equals(role.getRoleId()));
            if (assigned)
            {
                user.setPermissions(permissionService.getMenuPermission(user.getUser()));
                refreshToken(user);
            }
        }
    }

    private String getSessionId(HttpServletRequest request)
    {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies)
        {
            if (cookieName.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private ResponseCookie sessionCookie(String value, long maxAge)
    {
        return ResponseCookie.from(cookieName, value).httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/").maxAge(maxAge).build();
    }

    private String getTokenKey(String sessionId)
    {
        return CacheConstants.LOGIN_TOKEN_KEY + sessionId;
    }
}
