package com.zoutrankil.market.cn.rates.config;

import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.macro.interest.InterestRateDatasets;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.storage.QuestDbDateObservationTarget;
import com.zoutrankil.market.cn.rates.application.InterestObservationDailySync;
import com.zoutrankil.market.cn.rates.application.InterestObservationSyncOwner;
import com.zoutrankil.market.cn.rates.cli.InterestObservationCommands;

import io.questdb.client.QuestDB;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

/** 三条 owner 提供原 v2 读取布局及明确写入能力；不另注册重复的数据集。 */
@Configuration(proxyBeanMethods = false)
@Import(InterestObservationDailySync.class)
public class InterestObservationConfiguration {
    private InterestObservationSyncOwner owner(
            DatasetDefinition read,
            TusharePageService pages,
            JdbcTemplate jdbc,
            QuestDB questdb,
            String ledger) {
        var definition = InterestObservationSyncOwner.writable(read);
        return new InterestObservationSyncOwner(
                SourceContract.load(read.datasetId()),
                definition,
                new QuestDbDateObservationTarget(definition, jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    InterestObservationSyncOwner shiborObservationOwner(
            TusharePageService pages,
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return owner(InterestRateDatasets.SHIBOR, pages, jdbc, questdb, ledger);
    }

    @Bean
    InterestObservationSyncOwner hiborObservationOwner(
            TusharePageService pages,
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return owner(InterestRateDatasets.HIBOR, pages, jdbc, questdb, ledger);
    }

    @Bean
    InterestObservationSyncOwner lprObservationOwner(
            TusharePageService pages,
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return owner(InterestRateDatasets.LPR, pages, jdbc, questdb, ledger);
    }

    @Bean
    @ConditionalOnNotWebApplication
    InterestObservationCommands interestObservationCommands(
            List<InterestObservationSyncOwner> owners) {
        return new InterestObservationCommands(owners);
    }
}
