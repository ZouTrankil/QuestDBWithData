package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.index.policy.IndexDailyBasicUniverse;
import com.zoutrankil.data.domain.shared.SyncJobOwner;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical D020 one-code/date-window job declaration. */
@Service
public final class IndexDailyBasicSyncJobOwner implements SyncJobOwner {
    public static final int REVISION_DAYS = 5;
    public static final SyncJobDefinition DEFINITION =
            new SyncJobDefinition(
                    "data.index_daily_basic",
                    1,
                    "index_daily_basic",
                    1,
                    "index_daily_basic_owner",
                    Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE),
                    Mode.INCREMENTAL,
                    Map.of(
                            "targetId",
                            new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                            "tsCode",
                            new Parameter(
                                    ParameterType.STRING,
                                    true,
                                    10,
                                    1,
                                    IndexDailyBasicUniverseSet.CODES),
                            "checkpointAnchor",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "checkpointBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "targetMinBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "targetMaxBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
                    "tushare.shared",
                    "index_daily_basic.trade_date",
                    "questdb.full_key_values",
                    new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                    Duration.ofHours(12),
                    new Budget(
                            IndexDailyBasicSource.MAX_WINDOW_DAYS,
                            1,
                            1,
                            IndexDailyBasicSource.API_ROW_CAP,
                            1024 * 1024),
                    REVISION_DAYS,
                    List.of(),
                    Frequency.DAILY,
                    ZoneId.of("Asia/Shanghai"),
                    true,
                    true);

    @Override
    public String datasetId() {
        return "index_daily_basic";
    }

    @Override
    public Set<Mode> supportedSyncModes() {
        return DEFINITION.supportedModes();
    }

    @Override
    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(DEFINITION);
    }

    private static final class IndexDailyBasicUniverseSet {
        private static final Set<String> CODES = Set.copyOf(IndexDailyBasicUniverse.CORE_INDICES);
    }
}
