package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.data.domain.fund.EtfOverviewScope;
import com.zoutrankil.data.domain.stock.basic.StockBasicDataset;
import com.zoutrankil.data.domain.stock.reference.StockShockDatasets;
import com.zoutrankil.data.repository.CnBondYieldCurveWritePort;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.ReadGroupReader;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;

/** Explicit read representations preserve their Java types and the active physical definitions. */
@Configuration(proxyBeanMethods = false)
public class ReadGroupConfiguration {
    @Bean
    DatasetImplementation stockBasicLatestDataset() {
        return () -> StockBasicDataset.LATEST;
    }

    /** Keep the formal data definition available to this independently buildable app.
     * The main-strategy composition module supplies the bounded synchronization owner.
     */
    @Bean
    DatasetImplementation cnBondYieldCurveDataset() {
        return CnBondYieldCurveWritePort::definition;
    }

    // 只接现有表的明确读取契约，不注册新的同步 owner 或 writer。
    @Bean
    DatasetImplementation stockShockDataset() {
        return () -> StockShockDatasets.SHOCK;
    }

    @Bean
    DatasetImplementation stockHighShockDataset() {
        return () -> StockShockDatasets.HIGH_SHOCK;
    }

    @Bean
    ReadBindingCatalog readBindingCatalog(
            ObjectProvider<ReadBindingCatalog.Registration<?>> extensions,
            ObjectProvider<EtfOverviewScope> etfOverviewScopes,
            ObjectProvider<BacktestDailyScope> backtestScopes,
            @Value("${app.sync.etf-adj-schema-version:1}") int etfAdjVersion,
            @Value("${app.sync.etf-daily-schema-version:1}") int etfDailyVersion,
            @Value("${app.sync.etf-share-schema-version:1}") int etfShareVersion,
            @Value("${app.sync.stk-st-daily-schema-version:1}") int stDailyVersion,
            @Value("${app.sync.stk-suspend-schema-version:1}") int suspendVersion,
            @Value("${app.sync.l2-daily-features-schema-version:1}") int l2DailyVersion) {
        var registrations =
                new ArrayList<>(
                        DefaultReadBindings.registrations(
                                etfAdjVersion,
                                etfDailyVersion,
                                etfShareVersion,
                                stDailyVersion,
                                suspendVersion,
                                l2DailyVersion,
                                etfOverviewScopes
                                                        .getIfAvailable(
                                                                () -> EtfOverviewScope.LEGACY)
                                                        .legacy()
                                                && etfShareVersion == 1
                                                && etfDailyVersion == 1
                                        ? 1
                                        : 2,
                                backtestScopes
                                                .getIfAvailable(() -> BacktestDailyScope.LEGACY)
                                                .legacy()
                                        ? 1
                                        : 2));
        extensions.orderedStream().forEach(registrations::add);
        return new ReadBindingCatalog(registrations);
    }

    @Bean
    ReadGroupReader readGroupReader(
            DatasetRegistry datasets, QuestDbBoundedReader reader, ReadBindingCatalog catalog) {
        return new ReadGroupReader(datasets, reader, catalog.bind(datasets));
    }

    /** Direct callers can still assemble a partial registry with the standard representations. */
    ReadGroupReader readGroupReader(DatasetRegistry datasets, QuestDbBoundedReader reader) {
        return readGroupReader(
                datasets, reader, new ReadBindingCatalog(DefaultReadBindings.registrations()));
    }
}
