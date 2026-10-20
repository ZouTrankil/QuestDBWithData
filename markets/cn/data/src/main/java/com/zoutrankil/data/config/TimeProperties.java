package com.zoutrankil.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.Objects;

@ConfigurationProperties(prefix = "app.time")
public record TimeProperties(ZoneId businessZone) {
    public TimeProperties {
        Objects.requireNonNull(businessZone, "app.time.business-zone is required");
    }
}
