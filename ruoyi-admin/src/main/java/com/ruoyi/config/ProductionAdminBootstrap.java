package com.ruoyi.config;

import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.system.security.PasswordPolicyService;
import com.ruoyi.common.core.domain.entity.SysUser;

/** Performs the one-time production administrator bootstrap from a secret. */
@Component
@Profile("prod")
@Order(10)
public class ProductionAdminBootstrap implements ApplicationRunner
{
    private static final String SENTINEL = "!BOOTSTRAP_REQUIRED!";
    private static final Set<String> KNOWN_INITIAL_HASHES = Set.of(
        "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2");

    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicy;

    public ProductionAdminBootstrap(JdbcTemplate jdbc, Environment environment,
        PasswordEncoder passwordEncoder, PasswordPolicyService passwordPolicy)
    {
        this.jdbc = jdbc;
        this.environment = environment;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    @Override
    public void run(ApplicationArguments args)
    {
        String stored = jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id = 1", String.class);
        if (!SENTINEL.equals(stored) && !KNOWN_INITIAL_HASHES.contains(stored))
        {
            return;
        }
        String initialPassword = environment.getProperty("INITIAL_ADMIN_PASSWORD");
        if (!StringUtils.hasText(initialPassword))
        {
            throw new IllegalStateException(
                "INITIAL_ADMIN_PASSWORD must be supplied by the credential manager for first production startup");
        }
        SysUser admin = new SysUser();
        admin.setUserName("admin");
        admin.setNickName("系统管理员");
        passwordPolicy.validate(initialPassword, admin);
        int rows = jdbc.update("""
            UPDATE sys_user SET password = ?, status = '0', must_change_password = 1,
              pwd_update_date = NULL, update_by = 'secure-bootstrap', update_time = NOW()
            WHERE user_id = 1 AND password = ?
            """, passwordEncoder.encode(initialPassword), stored);
        if (rows != 1)
        {
            throw new IllegalStateException("administrator bootstrap was not applied atomically");
        }
    }
}
