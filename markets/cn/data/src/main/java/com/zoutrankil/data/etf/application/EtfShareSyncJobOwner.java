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

/** Canonical D016 declaration; shared configuration owns registration and rate-limit wiring. */
@Service
public final class EtfShareSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 366;
    public static final int MAX_ROWS_PER_DATE = EtfShareSource.MAX_ROWS_PER_DATE;
    public static final int MAX_REQUESTS_PER_DATE = EtfShareSource.REQUESTED_MARKETS.size();
    public static final int REVISION_DAYS = 5;

    public static final SyncJobDefinition DEFINITION =
            new SyncJobDefinition(
                    "data.etf_share",
                    3,
                    "etf_share",
                    1,
                    "etf_share_owner",
                    Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE),
                    Mode.INCREMENTAL,
                    Map.of(
                            "targetId",
                            new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                            "trade_dates",
                            new Parameter(ParameterType.STRING, true, 4000, 1, Set.of()),
                            "observedAt",
                            new Parameter(ParameterType.STRING, true, 40, 1, Set.of()),
                            "checkpointAnchor",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "checkpointBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "targetMinBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                            "targetMaxBefore",
                            new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
                    "tushare.shared",
                    "etf_share.trade_date",
                    "questdb.full_key_values",
                    new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                    Duration.ofHours(12),
                    new Budget(
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS * MAX_REQUESTS_PER_DATE,
                            MAX_WINDOW_DAYS * MAX_ROWS_PER_DATE,
                            1024 * 1024),
                    REVISION_DAYS,
                    List.of(new JobRef("data.exchange_calendar", 1)),
                    Frequency.DAILY,
                    ZoneId.of("Asia/Shanghai"),
                    true,
                    true);

    private final CnTradeDateLayout layout;

    public EtfShareSyncJobOwner() {
        this(1);
    }

    @Autowired
    public EtfShareSyncJobOwner(@Value("${app.sync.etf-share-schema-version:1}") int version) {
        layout = CnTradeDateLayout.fromVersion(version);
    }

    public static SyncJobDefinition forLayout(int version) {
        return definition(
                CnTradeDateLayout.fromVersion(version) == CnTradeDateLayout.LEGACY_V1 ? 3 : 4);
    }

    /** 作业 v3 对应布局 v1，作业 v4 对应布局 v2；不把两个版本号混用。 */
    public static SyncJobDefinition definition(int jobVersion) {
        var layout =
                switch (jobVersion) {
                    case 3 -> CnTradeDateLayout.LEGACY_V1;
                    case 4 -> CnTradeDateLayout.TRADE_DATE_V2;
                    default -> throw new IllegalArgumentException("未登记的 ETF 份额作业版本");
                };
        if (layout == CnTradeDateLayout.LEGACY_V1) return DEFINITION;
        var old = DEFINITION;
        return new SyncJobDefinition(
                old.jobId(),
                jobVersion,
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
        return "etf_share";
    }

    @Override
    public Set<Mode> supportedSyncModes() {
        return DEFINITION.supportedModes();
    }

    @Override
    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(forLayout(layout.schemaVersion()));
    }
}
