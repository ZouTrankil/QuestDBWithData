package com.zoutrankil.data.config;

import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.domain.DatasetImplementation;
import org.springframework.context.annotation.*;
import java.util.*;
import com.zoutrankil.data.domain.SyncJobOwner;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.service.SyncJobRegistry;

@Configuration
public class DatasetConfiguration {
    @Bean
    DatasetRegistry datasetRegistry(List<DatasetImplementation> implementations) {
        return new DatasetRegistry(implementations);
    }
    @Bean
    @Lazy
    SyncJobRegistry syncJobRegistry(
            DatasetRegistry datasets, List<SyncJobOwner> owners) {
        var modes = new HashMap<String, Set<SyncJobDefinition.Mode>>();
        var jobs = new ArrayList<SyncJobDefinition>();
        for (var owner : owners) {
            if (modes.putIfAbsent(owner.datasetId(), owner.supportedSyncModes()) != null)
                throw new IllegalArgumentException("Duplicate sync owner for dataset");
            for (var job : owner.syncJobDefinitions()) {
                if (!job.datasetId().equals(owner.datasetId())) throw new IllegalArgumentException("Job owner dataset mismatch");
                jobs.add(job);
            }
        }
        return new SyncJobRegistry(jobs, datasets, modes,
                new SyncJobRegistry.Policies(
                        Set.of("tushare.shared","file.bounded"), Set.of("stock_basic.snapshot","exchange_calendar.year","stock_detail_info.identity","index_catalog.file","ths_index.complete","index_member.l2_explicit","ths_member.board","daily.trade_date","daily_basic.trade_date","stk_factor.daily","stk_limit.trade_date","etf_daily.trade_date","etf_adj.trade_date","etf_share.trade_date","etf_factor.trade_date","index_daily_market.trade_date","index_daily_basic.trade_date","index_weight.index_month","index_monthly.month_window","dc_index.trade_date","moneyflow_hsgt.range31","moneyflow_dc.trade_date","moneyflow_ths.trade_date","moneyflow.trade_date","fund_portfolio.ann_date","etf_basic.market_snapshot","l2_manifest.parquet_date","l2_daily_features.parquet_date","l2_intraday_bar_features.parquet_date","l2_t0_training_labels.parquet_date","stk_st_daily.namechange_year_trade_dates"),
                        Set.of("questdb.full_key_values","questdb.full_row_stage_replace")));
    }
}
