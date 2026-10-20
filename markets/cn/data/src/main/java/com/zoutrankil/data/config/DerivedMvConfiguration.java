package com.zoutrankil.data.config;

import com.zoutrankil.data.derived.domain.RetailSentimentDailyLayout;
import com.zoutrankil.data.derived.port.MarketBreadthDailyTarget;
import com.zoutrankil.data.derived.port.RetailSentimentDailyTarget;
import com.zoutrankil.data.derived.storage.QuestDbMarketBreadthDailyTarget;
import com.zoutrankil.data.derived.storage.QuestDbRetailSentimentDailyTarget;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 装配同版 MV 会话；实际写入仍须通过部署准入。 */
@Configuration(proxyBeanMethods = false)
public class DerivedMvConfiguration {
    @Bean
    MarketBreadthDailyTarget marketBreadthDailyTarget(
            JdbcTemplate jdbc,
            QuestDbProperties properties,
            @Value("${app.sync.market-breadth-daily.mutations-enabled:false}") boolean enabled,
            @Value("${app.sync.market-breadth-daily.expected-target-id:}")
                    String expectedTargetId) {
        return new QuestDbMarketBreadthDailyTarget(jdbc, properties, enabled, expectedTargetId);
    }

    @Bean
    RetailSentimentDailyTarget retailSentimentDailyTarget(
            JdbcTemplate jdbc,
            QuestDbProperties properties,
            @Value("${app.sync.retail-sentiment-daily.mutations-enabled:false}") boolean enabled,
            @Value("${app.sync.retail-sentiment-daily.expected-target-id:}")
                    String expectedTargetId,
            @Value("${app.sync.l2-daily-features-schema-version:1}") int version,
            @Value("${app.sync.l2-daily-features.target-table:}") String source,
            @Value(
                            "${app.sync.retail-sentiment-daily.v2-target-table:java_d098_mv_retail_sentiment_daily_v2}")
                    String output) {
        var layout =
                version == 1
                        ? RetailSentimentDailyLayout.LEGACY
                        : new RetailSentimentDailyLayout(
                                CnTradeDateLayout.fromVersion(version), source, output);
        return new QuestDbRetailSentimentDailyTarget(
                jdbc, properties, enabled, expectedTargetId, layout);
    }
}
