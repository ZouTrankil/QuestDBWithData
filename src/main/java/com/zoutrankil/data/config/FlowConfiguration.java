package com.zoutrankil.data.config;

import com.zoutrankil.data.calendar.port.SseCalendarWindowReadPort;
import com.zoutrankil.data.calendar.storage.QuestDbSseCalendarWindowReadPort;
import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.flow.storage.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configures the four moneyflow targets and bounded physical calendar reader. */
@Configuration(proxyBeanMethods = false)
public class FlowConfiguration {
    @Bean MoneyflowTarget moneyflowTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.moneyflow-table:java_d024_moneyflow_acceptance}") String table) {
        return new QuestDbMoneyflowTarget(table, jdbc, questdb);
    }
    @Bean MoneyflowThsTarget moneyflowThsTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.moneyflow-ths-table:java_d025_moneyflow_ths_acceptance}") String table) {
        return new QuestDbMoneyflowThsTarget(table, jdbc, questdb);
    }
    @Bean MoneyflowDcTarget moneyflowDcTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.moneyflow-dc-table:java_d026_moneyflow_dc_acceptance}") String table) {
        return new QuestDbMoneyflowDcTarget(table, jdbc, questdb);
    }
    @Bean MoneyflowHsgtTarget moneyflowHsgtTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.moneyflow-hsgt-table:java_d027_moneyflow_hsgt_acceptance}") String table) {
        return new QuestDbMoneyflowHsgtTarget(table, jdbc, questdb);
    }
    @Bean SseCalendarWindowReadPort sseCalendarWindowReadPort(JdbcTemplate jdbc) {
        return new QuestDbSseCalendarWindowReadPort(jdbc);
    }
}
