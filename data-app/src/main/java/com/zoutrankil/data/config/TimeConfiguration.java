package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.temporal.BusinessTime;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TimeProperties.class)
public class TimeConfiguration {
    @Bean
    BusinessTime businessTime(TimeProperties properties) {
        return new BusinessTime(Clock.systemUTC(), properties.businessZone());
    }
}
