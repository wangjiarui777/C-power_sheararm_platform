package com.ruoyi.system.security;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;

/** Single server-side password policy for every account creation/reset path. */
@Component
public class PasswordPolicyService
{
    private static final Set<String> BLOCKED = Set.of(
            "admin123", "123456", "password", "password123", "qwerty123", "change-me");

    public void validate(String password, SysUser user)
    {
        if (password == null || password.length() < 12 || password.length() > 64)
            throw new ServiceException("密码长度必须在12到64个字符之间");
        String normalized = password.toLowerCase(Locale.ROOT);
        if (BLOCKED.contains(normalized)) throw new ServiceException("密码属于常见弱口令");
        if (user != null)
        {
            rejectRelated(normalized, user.getUserName(), "密码不能包含用户名");
            rejectRelated(normalized, user.getNickName(), "密码不能包含昵称");
            rejectRelated(normalized, user.getPhonenumber(), "密码不能包含手机号");
            rejectRelated(normalized, user.getEmail(), "密码不能包含邮箱");
        }
    }

    private void rejectRelated(String password, String value, String message)
    {
        if (value == null || value.isBlank()) return;
        String candidate = value.toLowerCase(Locale.ROOT).trim();
        int at = candidate.indexOf('@');
        if (at > 0) candidate = candidate.substring(0, at);
        if (candidate.length() >= 3 && password.contains(candidate)) throw new ServiceException(message);
    }
}
