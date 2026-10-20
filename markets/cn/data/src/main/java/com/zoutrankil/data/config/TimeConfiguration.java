package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.temporal.BusinessTime;
import com.zoutrankil.data.domain.temporal.MarketId;
import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.domain.temporal.MarketTimes;
import com.zoutrankil.runtime.MarketClocks;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TimeProperties.class)
public class TimeConfiguration {
    @Bean
    MarketTimes marketTimes(Environment environment) {
        return MarketClocks.load(environment, Clock.systemUTC());
    }

    @Bean
    MarketTime cnMarketTime(MarketTimes times) {
        return times.require(MarketId.CN);
    }

    @Bean
    BusinessTime businessTime(MarketTime cnMarketTime) {
        return new BusinessTime(cnMarketTime);
    }
}
