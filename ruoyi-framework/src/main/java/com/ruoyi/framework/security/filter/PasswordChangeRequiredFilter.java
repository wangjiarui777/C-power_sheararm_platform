package com.ruoyi.framework.security.filter;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ruoyi.common.core.domain.model.LoginUser;

/** Enforces the first-login password gate on the server, not merely in the UI. */
@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter
{
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser
                && loginUser.getUser() != null
                && Boolean.TRUE.equals(loginUser.getUser().getMustChangePassword())
                && !isAllowed(request))
        {
            response.setStatus(428);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":428,\"msg\":\"PASSWORD_CHANGE_REQUIRED\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowed(HttpServletRequest request)
    {
        String path = normalize(request.getRequestURI());
        if ("/getInfo".equals(path) || "/csrf".equals(path) || "/logout".equals(path)
            || "/system/user/profile/updatePwd".equals(path))
        {
            return true;
        }
        // 头像由布局和个人中心以普通图片请求加载；阻断它会产生无意义的
        // 428 资源错误，并在开发环境触发 webpack overlay。
        if ("GET".equalsIgnoreCase(request.getMethod()) && path.startsWith("/profile/avatar/"))
        {
            return true;
        }
        return "/system/user/profile".equals(path) && "GET".equalsIgnoreCase(request.getMethod());
    }

    private String normalize(String uri)
    {
        if (uri == null) return "";
        return uri.startsWith("/prod-api/") ? uri.substring("/prod-api".length()) : uri;
    }
}
