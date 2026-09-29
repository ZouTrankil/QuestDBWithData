package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Canonical D018 source job declaration; application-level registration remains shared integration work. */
@org.springframework.stereotype.Service
public final class EtfPortfolioSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 45;
    public static final int MAX_ROWS_PER_ANN_DATE = EtfPortfolioSource.MAX_ROWS_PER_ANN_DATE;
    public static final int MAX_RUNNER_CHUNKS_PER_ANN_DATE = EtfPortfolioSource.MAX_CHUNKS_PER_ANN_DATE;
    public static final int MAX_TOTAL_ROWS = 1_000_000;
    public static final int REVISION_DAYS = 30;
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.etf_portfolio", 1, "etf_portfolio", 1, "etf_portfolio_owner",
            Set.of(Mode.INCREMENTAL, Mode.BACKFILL), Mode.INCREMENTAL,
            Map.of("targetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "ann_dates", new Parameter(ParameterType.STRING, true, 404, 1, Set.of()),
                    "observedAt", new Parameter(ParameterType.STRING, true, 40, 1, Set.of()),
                    "checkpointAnchor", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "checkpointBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMinBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMaxBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
            "tushare.shared", "fund_portfolio.ann_date", "questdb.full_key_values",
            new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)), Duration.ofHours(12),
            new Budget(MAX_WINDOW_DAYS, MAX_WINDOW_DAYS * MAX_RUNNER_CHUNKS_PER_ANN_DATE,
                    MAX_WINDOW_DAYS * MAX_RUNNER_CHUNKS_PER_ANN_DATE,
                    MAX_TOTAL_ROWS, 1024 * 1024),
            REVISION_DAYS, List.of(), Frequency.DAILY, ZoneId.of("Asia/Shanghai"), true, true);

    @Override public String datasetId() { return "etf_portfolio"; }
    @Override public Set<Mode> supportedSyncModes() { return DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(DEFINITION); }
}
