package com.zoutrankil.data.config;

import com.zoutrankil.data.derived.port.MarketBreadthDailyV1Target;
import com.zoutrankil.data.derived.port.RetailSentimentDailyV1Target;
import com.zoutrankil.data.derived.storage.QuestDbMarketBreadthDailyV1Target;
import com.zoutrankil.data.derived.storage.QuestDbRetailSentimentDailyV1Target;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configures MV sessions while preserving deferred mutation admission. */
@Configuration(proxyBeanMethods = false)
public class DerivedMvConfiguration {
    @Bean MarketBreadthDailyV1Target marketBreadthDailyV1Target(JdbcTemplate jdbc, QuestDbProperties properties,
            @Value("${app.sync.market-breadth-daily.mutations-enabled:false}") boolean enabled,
            @Value("${app.sync.market-breadth-daily.expected-target-id:}") String expectedTargetId) {
        return new QuestDbMarketBreadthDailyV1Target(jdbc, properties, enabled, expectedTargetId);
    }
    @Bean RetailSentimentDailyV1Target retailSentimentDailyV1Target(JdbcTemplate jdbc, QuestDbProperties properties,
            @Value("${app.sync.retail-sentiment-daily.mutations-enabled:false}") boolean enabled,
            @Value("${app.sync.retail-sentiment-daily.expected-target-id:}") String expectedTargetId) {
        return new QuestDbRetailSentimentDailyV1Target(jdbc, properties, enabled, expectedTargetId);
    }
}
