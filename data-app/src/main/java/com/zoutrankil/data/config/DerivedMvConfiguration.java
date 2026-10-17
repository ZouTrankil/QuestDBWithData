package com.zoutrankil.data.config;

import com.zoutrankil.data.derived.port.MarketBreadthDailyTarget;
import com.zoutrankil.data.derived.port.RetailSentimentDailyTarget;
import com.zoutrankil.data.derived.storage.QuestDbMarketBreadthDailyTarget;
import com.zoutrankil.data.derived.storage.QuestDbRetailSentimentDailyTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configures MV sessions while preserving deferred mutation admission. */
@Configuration(proxyBeanMethods = false)
public class DerivedMvConfiguration {
    @Bean MarketBreadthDailyTarget marketBreadthDailyTarget(JdbcTemplate jdbc, QuestDbProperties properties,
            @Value("${app.sync.market-breadth-daily.mutations-enabled:false}") boolean enabled,
            @Value("${app.sync.market-breadth-daily.expected-target-id:}") String expectedTargetId) {
        return new QuestDbMarketBreadthDailyTarget(jdbc, properties, enabled, expectedTargetId);
    }
    @Bean RetailSentimentDailyTarget retailSentimentDailyTarget(JdbcTemplate jdbc, QuestDbProperties properties,
            @Value("${app.sync.retail-sentiment-daily.mutations-enabled:false}") boolean enabled,
            @Value("${app.sync.retail-sentiment-daily.expected-target-id:}") String expectedTargetId) {
        return new QuestDbRetailSentimentDailyTarget(jdbc, properties, enabled, expectedTargetId);
    }
}
