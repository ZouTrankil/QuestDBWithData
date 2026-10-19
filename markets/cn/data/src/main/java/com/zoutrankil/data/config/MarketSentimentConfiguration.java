package com.zoutrankil.data.config;

import com.zoutrankil.data.derived.storage.MarketSentimentDailyStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
public class MarketSentimentConfiguration {
    @Bean
    public MarketSentimentDailyStorage marketSentimentDailyStorage(JdbcTemplate jdbc,
            @Value("${app.sync.market-sentiment-table:market_sentiment_daily}") String table) {
        return new MarketSentimentDailyStorage(jdbc,table);
    }
}
