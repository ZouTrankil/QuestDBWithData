package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncJobOwner;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** D025 canonical per-dataset job declaration. The calendar is a verified D001 input. */
@org.springframework.stereotype.Service
public final class MoneyflowThsSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 366;
    public static final int REVISION_DAYS = 5;
    public static final LocalDate BOOTSTRAP_FROM = LocalDate.of(2026, 1, 1);
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.moneyflow_ths", 1, "moneyflow_ths", 1, "moneyflow_ths_owner",
            Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE), Mode.INCREMENTAL,
            Map.of("targetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "checkpointAnchor", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "checkpointBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMinBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMaxBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
            "tushare.shared", "moneyflow_ths.trade_date", "questdb.full_key_values",
            new RetryPolicy(3, Duration.ofSeconds(2), Duration.ofMinutes(10)), Duration.ofMinutes(30),
            new Budget(MAX_WINDOW_DAYS, MAX_WINDOW_DAYS, MAX_WINDOW_DAYS,
                    MAX_WINDOW_DAYS * MoneyflowThsSource.API_ROW_CAP, 1024 * 1024),
            REVISION_DAYS, List.of(new JobRef("data.exchange_calendar", 1)), Frequency.DAILY,
            ZoneId.of("Asia/Shanghai"), true, true);

    @Override public String datasetId() { return "moneyflow_ths"; }
    @Override public Set<Mode> supportedSyncModes() { return DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(DEFINITION); }

}
