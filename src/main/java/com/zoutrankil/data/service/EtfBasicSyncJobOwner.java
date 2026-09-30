package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Full directory snapshot owner; fund_basic has no date cursor for a safe row-level incremental mode. */
@org.springframework.stereotype.Service
public final class EtfBasicSyncJobOwner implements SyncJobOwner {
    public static final int MAX_ROWS = EtfBasicSource.SOURCE_ROW_CAP;
    public static final int MAX_WINDOW_PAGES = 2; // SyncJobRunner pages are at most 10,000 rows.
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.etf_basic", 1, "etf_basic", 1, "etf_basic_owner",
            Set.of(Mode.SNAPSHOT), Mode.SNAPSHOT,
            Map.of("targetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "observedAt", new Parameter(ParameterType.STRING, true, 40, 1, Set.of())),
            "tushare.shared", "etf_basic.market_snapshot", "questdb.full_key_values",
            new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)), Duration.ofMinutes(30),
            new Budget(1, MAX_WINDOW_PAGES, MAX_WINDOW_PAGES, MAX_ROWS, 1024 * 1024), 0,
            List.of(), Frequency.WEEKLY, ZoneId.of("Asia/Shanghai"), true, false);

    @Override public String datasetId() { return "etf_basic"; }
    @Override public Set<Mode> supportedSyncModes() { return DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(DEFINITION); }
}
