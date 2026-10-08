package com.zoutrankil.data.etf.application;


import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Canonical D015 job declaration; registration is wired by the shared application configuration. */
@org.springframework.stereotype.Service
public final class EtfAdjSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 366;
    public static final int MAX_ROWS_PER_DATE = EtfAdjSource.MAX_ROWS_PER_DATE;
    public static final int MAX_PAGES_PER_DATE = EtfAdjSource.MAX_PAGES_PER_DATE;
    public static final int REVISION_DAYS = 5;
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.etf_adj", 1, "etf_adj", 1, "etf_adj_owner",
            Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE), Mode.INCREMENTAL,
            Map.of("targetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "trade_dates", new Parameter(ParameterType.STRING, true, 4000, 1, Set.of()),
                    "checkpointAnchor", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "checkpointBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMinBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMaxBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
            "tushare.shared", "etf_adj.trade_date", "questdb.full_key_values",
            new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)), Duration.ofHours(12),
            new Budget(MAX_WINDOW_DAYS, MAX_WINDOW_DAYS, MAX_WINDOW_DAYS * MAX_PAGES_PER_DATE,
                    MAX_WINDOW_DAYS * MAX_ROWS_PER_DATE, 1024 * 1024), REVISION_DAYS,
            List.of(new JobRef("data.exchange_calendar", 1)), Frequency.DAILY, ZoneId.of("Asia/Shanghai"), true, true);
    @Override public String datasetId() { return "etf_adj"; }
    @Override public Set<Mode> supportedSyncModes() { return DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(DEFINITION); }
}
