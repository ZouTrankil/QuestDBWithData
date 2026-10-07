package com.zoutrankil.data.config;
import com.zoutrankil.data.calendar.port.ExchangeCalendarTarget;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarQuestDbTarget;
import com.zoutrankil.data.domain.ExchangeCalendarDataset;
import io.questdb.client.QuestDB;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration
public class CalendarConfiguration {
    @Bean public ExchangeCalendarTarget exchangeCalendarTarget(JdbcTemplate jdbc, @Lazy QuestDB questdb, QuestDbProperties properties) {
        return new ExchangeCalendarQuestDbTarget(ExchangeCalendarDataset.DEFINITION.objectName(),jdbc,questdb,properties);
    }
}
