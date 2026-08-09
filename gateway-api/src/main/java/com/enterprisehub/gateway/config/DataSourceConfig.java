package com.enterprisehub.gateway.config;

import com.enterprisehub.gateway.tenant.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Builds the DataSource from DataSourceProperties (bound from
 * spring.datasource.*) directly, rather than injecting Spring Boot's own
 * auto-configured DataSource bean and wrapping it -- injecting a DataSource
 * into a method that itself produces the @Primary DataSource bean causes
 * "bean currently in creation" circular reference errors, since Spring
 * can't disambiguate "the one being built" from "some other candidate" by
 * type alone. DataSourceProperties is a distinct bean type, so there's no
 * such ambiguity. This is Spring Boot's own documented pattern for
 * customizing the DataSource. DataSourceAutoConfiguration is
 * @ConditionalOnMissingBean(DataSource.class), so it backs off entirely
 * once this bean exists -- only one Hikari pool ever gets created.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource tenantAwareDataSource(DataSourceProperties properties) {
        DataSource actual = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        return new TenantAwareDataSource(actual);
    }
}
