package com.zoutrankil.questdbwithdata.config;

import java.time.ZoneId;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.time")
public record TimeProperties(ZoneId businessZone) {
    public TimeProperties {
        Objects.requireNonNull(businessZone, "app.time.business-zone is required");
    }
}
