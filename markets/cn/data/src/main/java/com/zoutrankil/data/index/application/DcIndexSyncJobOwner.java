package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.JobRef;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.index.domain.DcIndexRows;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical D023 owner. One unpaged source call per exchange trading date; five-day bounded windows. */
@Service
public final class DcIndexSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 5, REVISION_DAYS = 2;
    public static final int MAX_ROWS_PER_DATE = DcIndexSource.SOURCE_ROW_CAP;
    public static final SyncJobDefinition DEFINITION =
            new SyncJobDefinition(
                    "data.dc_index",
                    1,
                    "dc_index",
                    1,
                    "dc_index_owner",
                    Set.of(Mode.INCREMENTAL, Mode.BACKFILL),
                    Mode.INCREMENTAL,
                    Map.of(
                            "targetId",
                            new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                            "physicalTargetId",
                            new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                            "trade_dates",
                            new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                            "checkpointAnchor",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "checkpointBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "targetMinBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "targetMaxBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
                    "tushare.shared",
                    "dc_index.trade_date",
                    "questdb.full_key_values",
                    new RetryPolicy(1, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                    Duration.ofHours(2),
                    new Budget(
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS * MAX_ROWS_PER_DATE,
                            DcIndexRows.MAX_BATCH_BYTES),
                    REVISION_DAYS,
                    List.of(new JobRef("data.exchange_calendar", 1)),
                    Frequency.DAILY,
                    ZoneId.of("Asia/Shanghai"),
                    true,
                    true);

    @Override
    public String datasetId() {
        return "dc_index";
    }

    @Override
    public Set<Mode> supportedSyncModes() {
        return DEFINITION.supportedModes();
    }

    @Override
    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(DEFINITION);
    }
}
