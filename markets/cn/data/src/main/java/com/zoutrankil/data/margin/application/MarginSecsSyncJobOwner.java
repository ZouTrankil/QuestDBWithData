package com.zoutrankil.data.margin.application;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.domain.stock.margin.MarginSecsDataset;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical bounded daily owner for D030. */
@Service
public final class MarginSecsSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 31,
            MAX_SOURCE_SLICES = 31,
            MAX_RUN_ROWS = MAX_SOURCE_SLICES * MarginSecsSource.API_ROW_CAP,
            REVISION_DAYS = 5;
    public static final SyncJobDefinition DEFINITION =
            new SyncJobDefinition(
                    "data.margin_secs",
                    1,
                    "margin_secs",
                    1,
                    "margin_secs_owner",
                    Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE),
                    Mode.INCREMENTAL,
                    Map.ofEntries(
                            Map.entry(
                                    "targetId",
                                    new Parameter(ParameterType.STRING, true, 128, 1, Set.of())),
                            Map.entry(
                                    "physicalTargetId",
                                    new Parameter(ParameterType.STRING, true, 128, 1, Set.of())),
                            Map.entry(
                                    "targetRowsBefore",
                                    new Parameter(ParameterType.INTEGER, true, 10, 1, Set.of())),
                            Map.entry(
                                    "targetFingerprint",
                                    new Parameter(ParameterType.STRING, true, 64, 1, Set.of())),
                            Map.entry(
                                    "targetMinBefore",
                                    new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
                            Map.entry(
                                    "targetMaxBefore",
                                    new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
                            Map.entry(
                                    "checkpointAnchor",
                                    new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
                            Map.entry(
                                    "checkpointBefore",
                                    new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
                            Map.entry(
                                    "calendarFingerprint",
                                    new Parameter(ParameterType.STRING, true, 64, 1, Set.of())),
                            Map.entry(
                                    "calendarDays",
                                    new Parameter(ParameterType.STRING, true, 400, 1, Set.of())),
                            Map.entry(
                                    "tradeDates",
                                    new Parameter(ParameterType.STRING, true, 310, 1, Set.of()))),
                    "tushare.shared",
                    "margin.trade_date.daily",
                    "questdb.year_wal_dedup",
                    new RetryPolicy(3, Duration.ofSeconds(2), Duration.ofMinutes(10)),
                    Duration.ofMinutes(45),
                    new Budget(
                            MAX_WINDOW_DAYS,
                            MAX_SOURCE_SLICES,
                            MAX_SOURCE_SLICES,
                            MAX_RUN_ROWS,
                            1024 * 1024),
                    REVISION_DAYS,
                    List.of(),
                    Frequency.DAILY,
                    ZoneId.of("Asia/Shanghai"),
                    true,
                    true);

    /** 正式v2只认证请求窗口；v1全表基线和原指纹保持原样。 */
    public static final SyncJobDefinition FORMAL_DEFINITION = formalDefinition();

    private static SyncJobDefinition formalDefinition() {
        var d = DEFINITION;
        var parameters = new LinkedHashMap<>(d.parameters());
        parameters.put(
                "baselineScope",
                new Parameter(ParameterType.STRING, true, 16, 1, Set.of("DATE_WINDOW")));
        return new SyncJobDefinition(
                d.jobId(),
                2,
                d.datasetId(),
                d.datasetVersion(),
                d.owner(),
                Set.of(Mode.BACKFILL),
                Mode.BACKFILL,
                parameters,
                d.ratePolicyRef(),
                d.slicePolicyRef(),
                d.verificationPolicyRef(),
                d.retry(),
                d.timeout(),
                d.budget(),
                d.revisionDays(),
                d.dependencies(),
                d.frequency(),
                d.zone(),
                d.enabled(),
                false);
    }

    private final boolean formal;

    public MarginSecsSyncJobOwner() {
        this("java_d030_margin_secs_acceptance");
    }

    @Autowired
    public MarginSecsSyncJobOwner(
            @Value(
                            "${app.sync.margin-secs-write-table:${app.sync.margin-secs-table:java_d030_margin_secs_acceptance}}")
                    String table) {
        MarginSecsDataset.requireExecutionTable(table);
        formal = "margin_secs".equals(table);
    }

    @Override
    public String datasetId() {
        return "margin_secs";
    }

    @Override
    public Set<Mode> supportedSyncModes() {
        return (formal ? FORMAL_DEFINITION : DEFINITION).supportedModes();
    }

    @Override
    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(formal ? FORMAL_DEFINITION : DEFINITION);
    }
}
