package com.zoutrankil.questdbwithdata.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchemaMigrationProperties.class)
public class SchemaMigrationConfiguration {
    @Bean
    @Lazy
    Flyway questDbFlyway(DataSource dataSource, SchemaMigrationProperties properties) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(properties.location())
                .table(properties.historyTable())
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .executeInTransaction(false)
                .load();
    }
}
