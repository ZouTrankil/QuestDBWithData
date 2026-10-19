package com.zoutrankil.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.migration")
public record SchemaMigrationProperties(String location, String historyTable) {}
