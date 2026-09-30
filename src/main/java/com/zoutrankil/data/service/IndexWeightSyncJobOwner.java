package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** D021 job contract. Snapshot mode is unbounded in trade_date because sources return latest snapshots. */
@org.springframework.stereotype.Service
public final class IndexWeightSyncJobOwner implements SyncJobOwner {
    public static final int MAX_BACKFILL_DAYS = 92;
    public static final int MAX_MONTH_SLICES = 32;
    public static final int MAX_SOURCE_ROWS_PER_MONTH = IndexWeightSource.TUSHARE_ROW_CAP;
    public static final int MAX_RUN_ROWS = MAX_MONTH_SLICES * MAX_SOURCE_ROWS_PER_MONTH;
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.index_weight", 1, "index_weight", 1, "index_weight_owner",
            Set.of(Mode.SNAPSHOT, Mode.BACKFILL), Mode.SNAPSHOT,
            Map.of("targetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "stockDetailTargetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "observedAt", new Parameter(ParameterType.STRING, true, 40, 1, Set.of()),
                    "force", new Parameter(ParameterType.BOOLEAN, false, 5, 1, Set.of()),
                    "targetMinBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMaxBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
            "tushare.shared", "index_weight.index_month", "questdb.full_key_values",
            new RetryPolicy(2, Duration.ofSeconds(1), Duration.ofMinutes(4)), Duration.ofMinutes(30),
            new Budget(MAX_BACKFILL_DAYS, MAX_MONTH_SLICES, MAX_MONTH_SLICES, MAX_RUN_ROWS, 1024 * 1024),
            0, List.of(new JobRef("data.index", 1), new JobRef("data.stock_detail_info", 1)),
            Frequency.WEEKLY, ZoneId.of("Asia/Shanghai"), true, false);
    @Override public String datasetId() { return IndexWeightDatasetId.VALUE; }
    @Override public Set<Mode> supportedSyncModes() { return DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(DEFINITION); }
    private static final class IndexWeightDatasetId { static final String VALUE = "index_weight"; }
}
