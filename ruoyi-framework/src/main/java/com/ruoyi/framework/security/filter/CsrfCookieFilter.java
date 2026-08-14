package com.ruoyi.framework.security.filter;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces the deferred CSRF token to be materialized on every browser request.
 * Without this, CookieCsrfTokenRepository may emit an empty XSRF-TOKEN cookie
 * on a later authenticated GET, causing the next POST to be rejected with 403.
 */
@Component
public class CsrfCookieFilter extends OncePerRequestFilter
{
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException
    {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken == null)
        {
            csrfToken = (CsrfToken) request.getAttribute("_csrf");
        }
        if (csrfToken != null)
        {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
