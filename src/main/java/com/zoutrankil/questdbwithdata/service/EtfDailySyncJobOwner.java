package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Canonical D014 job declaration; registration is wired by the shared application configuration. */
@org.springframework.stereotype.Service
public final class EtfDailySyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 366;
    public static final int REVISION_DAYS = 5;
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.etf_daily", 1, "etf_daily", 1, "etf_daily_owner",
            Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE), Mode.INCREMENTAL,
            Map.of("targetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "trade_dates", new Parameter(ParameterType.STRING, true, 4000, 1, Set.of()),
                    "checkpointAnchor", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "checkpointBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMinBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMaxBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
            "tushare.shared", "etf_daily.trade_date", "questdb.full_key_values",
            new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)), Duration.ofHours(12),
            new Budget(MAX_WINDOW_DAYS, MAX_WINDOW_DAYS, MAX_WINDOW_DAYS,
                    MAX_WINDOW_DAYS * EtfDailySource.API_ROW_CAP, 1024 * 1024), REVISION_DAYS,
            List.of(new JobRef("data.exchange_calendar", 1)), Frequency.DAILY, ZoneId.of("Asia/Shanghai"), true, true);
    @Override public String datasetId() { return "etf_daily"; }
    @Override public Set<Mode> supportedSyncModes() { return DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(DEFINITION); }
}
