package com.zoutrankil.data.config;

import com.zoutrankil.data.derived.port.EquityStyleMonthlySourceReadPort;
import com.zoutrankil.data.derived.port.EquityStyleMonthlyTarget;
import com.zoutrankil.data.derived.port.MacroCoreMonthlySourceReadPort;
import com.zoutrankil.data.derived.port.MacroCoreMonthlyTarget;
import com.zoutrankil.data.derived.storage.QuestDbEquityStyleMonthlySourceReader;
import com.zoutrankil.data.derived.storage.QuestDbEquityStyleMonthlyTarget;
import com.zoutrankil.data.derived.storage.QuestDbMacroCoreMonthlySourceReader;
import com.zoutrankil.data.derived.storage.QuestDbMacroCoreMonthlyTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configures independent monthly source reads and fresh writer factories. */
@Configuration(proxyBeanMethods = false)
public class DerivedMonthlyConfiguration {
    @Bean EquityStyleMonthlySourceReadPort equityStyleMonthlySourceReadPort(JdbcTemplate jdbc,
            @Value("${app.sync.equity-style-monthly.source-table:index_monthly}") String sourceTable) {
        return new QuestDbEquityStyleMonthlySourceReader(jdbc, sourceTable);
    }
    @Bean EquityStyleMonthlyTarget equityStyleMonthlyTarget(JdbcTemplate jdbc, QuestDbProperties properties,
            @Value("${app.sync.equity-style-monthly.target-table:}") String targetTable) {
        return new QuestDbEquityStyleMonthlyTarget(jdbc, properties, targetTable);
    }
    @Bean MacroCoreMonthlySourceReadPort macroCoreMonthlySourceReadPort(JdbcTemplate jdbc) {
        return new QuestDbMacroCoreMonthlySourceReader(jdbc);
    }
    @Bean MacroCoreMonthlyTarget macroCoreMonthlyTarget(JdbcTemplate jdbc, QuestDbProperties properties,
            @Value("${app.sync.macro-core-monthly.target-table:}") String targetTable) {
        return new QuestDbMacroCoreMonthlyTarget(jdbc, properties, targetTable);
    }
}
