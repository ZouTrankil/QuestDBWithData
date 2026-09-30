package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** The single bounded stk_suspend owner; actual execution remains explicitly invoked. */
@org.springframework.stereotype.Service
public final class StockSuspendSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 5;
    public static final int REVISION_DAYS = 2;
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.stk_suspend", 1, "stk_suspend", 1, "stk_suspend_owner",
            Set.of(Mode.INCREMENTAL, Mode.BACKFILL), Mode.INCREMENTAL,
            Map.of("targetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "physicalTargetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "checkpointAnchor", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "checkpointBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMinBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMaxBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
            "tushare.shared", "daily.trade_date", "questdb.full_key_values",
            new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)), Duration.ofMinutes(10),
            new Budget(MAX_WINDOW_DAYS, MAX_WINDOW_DAYS, MAX_WINDOW_DAYS,
                    MAX_WINDOW_DAYS * StockSuspendSource.SOURCE_ROW_CAP, 1024 * 1024),
            REVISION_DAYS, List.of(), Frequency.DAILY, ZoneId.of("Asia/Shanghai"), true, true);

    @Override public String datasetId() { return "stk_suspend"; }
    @Override public Set<Mode> supportedSyncModes() { return DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(DEFINITION); }
}
