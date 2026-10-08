package com.zoutrankil.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.web")
public record WebApiProperties(String apiToken, String allowedOrigins) {
    public WebApiProperties {
        apiToken = apiToken == null ? "" : apiToken.trim();
        allowedOrigins = allowedOrigins == null || allowedOrigins.isBlank() ? "*" : allowedOrigins.trim();
        if (!apiToken.isEmpty() && apiToken.length() < 24) {
            throw new IllegalArgumentException("app.web.api-token must contain at least 24 characters when configured");
        }
    }
}
