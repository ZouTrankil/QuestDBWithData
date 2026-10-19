package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.temporal.*;
import com.zoutrankil.runtime.MarketClocks;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TimeProperties.class)
public class TimeConfiguration {
    @Bean MarketTimes marketTimes(Environment environment) { return MarketClocks.load(environment,Clock.systemUTC()); }
    @Bean MarketTime cnMarketTime(MarketTimes times) { return times.require(MarketId.CN); }
    @Bean BusinessTime businessTime(MarketTime cnMarketTime) { return new BusinessTime(cnMarketTime); }
}
