package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Canonical D019 single-code job declaration; shared registry wiring remains coordinator-owned. */
@org.springframework.stereotype.Service
public final class IndexDailyMarketSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = IndexDailyMarketSource.MAX_WINDOW_DAYS;
    public static final int REVISION_DAYS = 5;
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.index_daily_market", 1, "index_daily_market", 1, "index_daily_market_owner",
            Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE), Mode.INCREMENTAL,
            Map.of("targetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "tsCode", new Parameter(ParameterType.STRING, true, 10, 1, Set.of()),
                    "route", new Parameter(ParameterType.STRING, true, 16, 1, Set.of()),
                    "observedAt", new Parameter(ParameterType.STRING, true, 32, 1, Set.of()),
                    "checkpointAnchor", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "checkpointBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMinBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMaxBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
            "tushare.shared", "index_daily_market.trade_date", "questdb.full_key_values",
            new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)), Duration.ofHours(12),
            new Budget(MAX_WINDOW_DAYS, 1, 1, IndexDailyMarketSource.API_ROW_CAP, 1024 * 1024),
            REVISION_DAYS, List.of(new JobRef("data.index", 1), new JobRef("data.index_member", 1)),
            Frequency.DAILY, ZoneId.of("Asia/Shanghai"), true, true);
    @Override public String datasetId() { return "index_daily_market"; }
    @Override public Set<Mode> supportedSyncModes() { return DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(DEFINITION); }
}
