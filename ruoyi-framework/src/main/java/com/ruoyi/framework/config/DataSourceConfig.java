package com.ruoyi.framework.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;

/** Explicit Hikari data sources for the system and isolated low-code databases. */
@Configuration
public class DataSourceConfig
{
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties systemDataSourceProperties()
    {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(
        @Qualifier("systemDataSourceProperties") DataSourceProperties properties)
    {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "jdbcTemplate")
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource)
    {
        return new JdbcTemplate(dataSource);
    }
}
