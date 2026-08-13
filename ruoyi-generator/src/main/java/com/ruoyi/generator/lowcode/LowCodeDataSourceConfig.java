package com.ruoyi.generator.lowcode;

import javax.sql.DataSource;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;

/** Dedicated least-privilege datasource for low-code business tables. */
@Configuration
public class LowCodeDataSourceConfig
{
    @Bean(name = "lowCodeBusinessDataSource")
    @ConfigurationProperties("lowcode.datasource")
    public DataSource lowCodeBusinessDataSource()
    {
        return new DruidDataSource();
    }

    @Bean(name = "lowCodeBusinessJdbcTemplate")
    public JdbcTemplate lowCodeBusinessJdbcTemplate(
        @Qualifier("lowCodeBusinessDataSource") DataSource lowCodeBusinessDataSource)
    {
        return new JdbcTemplate(lowCodeBusinessDataSource);
    }
}
