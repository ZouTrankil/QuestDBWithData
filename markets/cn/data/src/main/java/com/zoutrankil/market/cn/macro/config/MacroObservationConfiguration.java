package com.zoutrankil.market.cn.macro.config;

import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.macro.application.MacroObservationDailySync;
import com.zoutrankil.market.cn.macro.application.MacroObservationSyncOwner;
import com.zoutrankil.market.cn.macro.cli.MacroObservationCommands;
import com.zoutrankil.market.cn.macro.mapper.MacroObservationDefinition;
import com.zoutrankil.market.cn.macro.storage.QuestDbMacroObservationTarget;

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

/** 正式数据服务复用来源契约；不启用 Batch 隔离 writer，也不自动建表或调度。 */
@Configuration(proxyBeanMethods = false)
@Import(MacroObservationDailySync.class)
public class MacroObservationConfiguration {
    private MacroObservationSyncOwner owner(
            String dataset,
            TusharePageService pages,
            JdbcTemplate jdbc,
            QuestDB questdb,
            String ledger) {
        var source = SourceContract.load(dataset);
        var definition = MacroObservationDefinition.from(source);
        return new MacroObservationSyncOwner(
                source,
                definition,
                new QuestDbMacroObservationTarget(definition, jdbc, questdb),
                pages,
                Path.of(ledger));
    }

    @Bean
    MacroObservationSyncOwner cpiObservations(
            TusharePageService pages,
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return owner("cn_cpi", pages, jdbc, questdb, ledger);
    }

    @Bean
    MacroObservationSyncOwner ppiObservations(
            TusharePageService pages,
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return owner("cn_ppi", pages, jdbc, questdb, ledger);
    }

    @Bean
    MacroObservationSyncOwner pmiObservations(
            TusharePageService pages,
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return owner("cn_pmi", pages, jdbc, questdb, ledger);
    }

    @Bean
    MacroObservationSyncOwner moneySupplyObservations(
            TusharePageService pages,
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return owner("cn_m", pages, jdbc, questdb, ledger);
    }

    @Bean
    MacroObservationSyncOwner socialFinancingObservations(
            TusharePageService pages,
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return owner("sf_month", pages, jdbc, questdb, ledger);
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> socialFinancingObservationRead() {
        return ReadBindingCatalog.generic("sf_month", 1);
    }

    @Bean
    MacroObservationSyncOwner gdpObservations(
            TusharePageService pages,
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        return owner("cn_gdp", pages, jdbc, questdb, ledger);
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> cpiObservationRead() {
        return ReadBindingCatalog.generic("cn_cpi", 1);
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> ppiObservationRead() {
        return ReadBindingCatalog.generic("cn_ppi", 1);
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> pmiObservationRead() {
        return ReadBindingCatalog.generic("cn_pmi", 1);
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> moneySupplyObservationRead() {
        return ReadBindingCatalog.generic("cn_m", 1);
    }

    @Bean
    ReadBindingCatalog.Registration<DatasetValues> gdpObservationRead() {
        return ReadBindingCatalog.generic("cn_gdp", 1);
    }

    @Bean
    @ConditionalOnNotWebApplication
    MacroObservationCommands macroObservationCommands(List<MacroObservationSyncOwner> owners) {
        return new MacroObservationCommands(owners);
    }
}
