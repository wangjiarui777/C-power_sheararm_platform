package com.ruoyi.lowcode.core;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;

/** Dedicated least-privilege datasource for low-code business tables. */
@Configuration
public class LowCodeDataSourceConfig
{
    @Bean(name = "lowCodeBusinessDataSourceProperties")
    @ConfigurationProperties("lowcode.datasource")
    public DataSourceProperties lowCodeBusinessDataSourceProperties()
    {
        return new DataSourceProperties();
    }

    @Bean(name = "lowCodeBusinessDataSource")
    @ConfigurationProperties("lowcode.datasource.hikari")
    public HikariDataSource lowCodeBusinessDataSource(
        @Qualifier("lowCodeBusinessDataSourceProperties") DataSourceProperties properties)
    {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "lowCodeBusinessJdbcTemplate")
    public JdbcTemplate lowCodeBusinessJdbcTemplate(
        @Qualifier("lowCodeBusinessDataSource") DataSource lowCodeBusinessDataSource)
    {
        return new JdbcTemplate(lowCodeBusinessDataSource);
    }
}
