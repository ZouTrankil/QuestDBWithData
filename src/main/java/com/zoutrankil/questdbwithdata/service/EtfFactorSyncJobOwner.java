package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Canonical D017 owner. fund_factor_pro is requested once per frozen SSE trade date. */
@org.springframework.stereotype.Service
public final class EtfFactorSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 366;
    public static final int REVISION_DAYS = 5;
    public static final int MAX_ROWS_PER_DATE = EtfFactorSource.SOURCE_ROW_CAP;
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.etf_factor", 1, "etf_factor", 1, "etf_factor_owner",
            Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE), Mode.INCREMENTAL,
            Map.of("targetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "trade_dates", new Parameter(ParameterType.STRING, true, 4000, 1, Set.of()),
                    "checkpointAnchor", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "checkpointBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMinBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMaxBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
            "tushare.shared", "etf_factor.trade_date", "questdb.full_key_values",
            new RetryPolicy(3, Duration.ofSeconds(2), Duration.ofMinutes(2)), Duration.ofHours(12),
            new Budget(MAX_WINDOW_DAYS, MAX_WINDOW_DAYS, MAX_WINDOW_DAYS,
                    MAX_WINDOW_DAYS * MAX_ROWS_PER_DATE, 1024 * 1024), REVISION_DAYS,
            List.of(new JobRef("data.exchange_calendar", 1)), Frequency.DAILY,
            ZoneId.of("Asia/Shanghai"), true, true);
    @Override public String datasetId() { return "etf_factor"; }
    @Override public Set<Mode> supportedSyncModes() { return DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(DEFINITION); }
}
