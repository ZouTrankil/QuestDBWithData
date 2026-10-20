package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.JobRef;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical D015 job declaration; registration is wired by the shared application configuration. */
@Service
public final class EtfAdjSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 366;
    public static final int MAX_ROWS_PER_DATE = EtfAdjSource.MAX_ROWS_PER_DATE;
    public static final int MAX_PAGES_PER_DATE = EtfAdjSource.MAX_PAGES_PER_DATE;
    public static final int REVISION_DAYS = 5;
    public static final SyncJobDefinition DEFINITION =
            new SyncJobDefinition(
                    "data.etf_adj",
                    1,
                    "etf_adj",
                    1,
                    "etf_adj_owner",
                    Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE),
                    Mode.INCREMENTAL,
                    Map.of(
                            "targetId",
                            new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                            "trade_dates",
                            new Parameter(ParameterType.STRING, true, 4000, 1, Set.of()),
                            "checkpointAnchor",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "checkpointBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "targetMinBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "targetMaxBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
                    "tushare.shared",
                    "etf_adj.trade_date",
                    "questdb.full_key_values",
                    new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                    Duration.ofHours(12),
                    new Budget(
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS * MAX_PAGES_PER_DATE,
                            MAX_WINDOW_DAYS * MAX_ROWS_PER_DATE,
                            1024 * 1024),
                    REVISION_DAYS,
                    List.of(new JobRef("data.exchange_calendar", 1)),
                    Frequency.DAILY,
                    ZoneId.of("Asia/Shanghai"),
                    true,
                    true);

    private final CnTradeDateLayout layout;

    public EtfAdjSyncJobOwner() {
        this(1);
    }

    @Autowired
    public EtfAdjSyncJobOwner(@Value("${app.sync.etf-adj-schema-version:1}") int schemaVersion) {
        layout = CnTradeDateLayout.fromVersion(schemaVersion);
    }

    /** v1 原对象保持不动；新计划按显式配置注册，旧请求另按原版本恢复。 */
    public static SyncJobDefinition definition(int version) {
        var layout = CnTradeDateLayout.fromVersion(version);
        if (layout == CnTradeDateLayout.LEGACY_V1) return DEFINITION;
        var old = DEFINITION;
        return new SyncJobDefinition(
                old.jobId(),
                version,
                old.datasetId(),
                layout.schemaVersion(),
                old.owner(),
                old.supportedModes(),
                old.defaultMode(),
                old.parameters(),
                old.ratePolicyRef(),
                old.slicePolicyRef(),
                old.verificationPolicyRef(),
                old.retry(),
                old.timeout(),
                old.budget(),
                old.revisionDays(),
                old.dependencies(),
                old.frequency(),
                old.zone(),
                old.enabled(),
                old.dailyEligible());
    }

    @Override
    public String datasetId() {
        return "etf_adj";
    }

    @Override
    public Set<Mode> supportedSyncModes() {
        return DEFINITION.supportedModes();
    }

    @Override
    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(definition(layout.schemaVersion()));
    }
}
