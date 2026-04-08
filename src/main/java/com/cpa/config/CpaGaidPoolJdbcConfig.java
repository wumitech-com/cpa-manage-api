package com.cpa.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * cpa 库独立数据源（仅用于 GAID 池统计），与业务 tt 库分离。
 */
@Configuration
@ConditionalOnProperty(prefix = "cpa.gaid-pool-guard", name = "enabled", havingValue = "true")
public class CpaGaidPoolJdbcConfig {

    @Bean(name = "cpaGaidDataSource")
    public DataSource cpaGaidDataSource(CpaGaidPoolProperties props) {
        CpaGaidPoolProperties.DataSourceSettings ds = props.getDatasource();
        if (ds.getUrl() == null || ds.getUrl().isEmpty()) {
            throw new IllegalStateException("cpa.gaid-pool-guard.datasource.url 未配置");
        }
        return DataSourceBuilder.create()
                .url(ds.getUrl())
                .username(ds.getUsername())
                .password(ds.getPassword())
                .driverClassName(ds.getDriverClassName() != null ? ds.getDriverClassName() : "com.mysql.cj.jdbc.Driver")
                .build();
    }

    @Bean(name = "cpaGaidJdbcTemplate")
    public JdbcTemplate cpaGaidJdbcTemplate(@Qualifier("cpaGaidDataSource") DataSource cpaGaidDataSource) {
        return new JdbcTemplate(cpaGaidDataSource);
    }
}
