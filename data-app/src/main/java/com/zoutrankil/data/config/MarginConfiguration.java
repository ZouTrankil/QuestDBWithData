package com.zoutrankil.data.config;

import com.zoutrankil.data.margin.port.*;
import com.zoutrankil.data.margin.storage.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configures four independent financing and securities-lending targets. */
@Configuration(proxyBeanMethods = false)
public class MarginConfiguration {
    @Bean MarginAllTarget marginAllTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.margin-all-table:java_d028_margin_all_acceptance}") String table) {
        return new QuestDbMarginAllTarget(table, jdbc, questdb);
    }
    @Bean MarginDetailTarget marginDetailTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.margin-detail-table:java_d029_margin_detail_acceptance}") String table) {
        return new QuestDbMarginDetailTarget(table, jdbc, questdb);
    }
    @Bean MarginSecsTarget marginSecsTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.margin-secs-table:java_d030_margin_secs_acceptance}") String table) {
        return new QuestDbMarginSecsTarget(table, jdbc, questdb);
    }
    @Bean MarginZrzTarget marginZrzTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.margin-zrz-table:java_d031_margin_zrz_acceptance}") String table) {
        return new QuestDbMarginZrzTarget(table, jdbc, questdb);
    }
}
