package com.zoutrankil.market.cn.chips.config;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.chips.application.CyqPerfSyncOwner;
import com.zoutrankil.market.cn.chips.cli.CyqPerfCommands;
import com.zoutrankil.market.cn.chips.storage.QuestDbCyqPerfTarget;

import io.questdb.client.QuestDB;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

/** 只登记一个正式筹码 owner；沿用共享账本、来源配额和既有物理表。 */
@Configuration(proxyBeanMethods = false)
public class CyqPerfConfiguration {
    @Bean
    CyqPerfSyncOwner cyqPerfOwner(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            TusharePageService pages,
            ExchangeCalendarReadPort calendars,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return new CyqPerfSyncOwner(
                new QuestDbCyqPerfTarget(jdbc, questdb), pages, calendars, Path.of(ledger));
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> cyqPerfRead() {
        return ReadBindingCatalog.generic("cyq_perf", 1);
    }

    @Bean
    @ConditionalOnNotWebApplication
    CyqPerfCommands cyqPerfCommands(CyqPerfSyncOwner owner) {
        return new CyqPerfCommands(owner);
    }
}
