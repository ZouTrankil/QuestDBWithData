package com.zoutrankil.data.config;

import com.zoutrankil.data.group.port.WriteGroupWriters;
import com.zoutrankil.data.group.storage.QuestDbWriteGroupWriters;
import io.questdb.client.QuestDB;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class WriteGroupConfiguration {
    @Bean WriteGroupWriters writeGroupWriters(JdbcTemplate jdbc, @Lazy QuestDB questdb) {
        return new QuestDbWriteGroupWriters(jdbc, questdb);
    }
}
