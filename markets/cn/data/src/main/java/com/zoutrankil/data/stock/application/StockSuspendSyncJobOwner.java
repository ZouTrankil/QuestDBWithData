package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The single bounded stk_suspend owner; actual execution remains explicitly invoked. */
@Service
public final class StockSuspendSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS = 5;
    public static final int REVISION_DAYS = 2;
    public static final SyncJobDefinition DEFINITION =
            new SyncJobDefinition(
                    "data.stk_suspend",
                    1,
                    "stk_suspend",
                    1,
                    "stk_suspend_owner",
                    Set.of(Mode.INCREMENTAL, Mode.BACKFILL),
                    Mode.INCREMENTAL,
                    Map.of(
                            "targetId",
                            new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                            "physicalTargetId",
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
                    "daily.trade_date",
                    "questdb.full_key_values",
                    new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                    Duration.ofMinutes(10),
                    new Budget(
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS * StockSuspendSource.SOURCE_ROW_CAP,
                            1024 * 1024),
                    REVISION_DAYS,
                    List.of(),
                    Frequency.DAILY,
                    ZoneId.of("Asia/Shanghai"),
                    true,
                    true);

    /** v1 原定义保持不动；显式分版供原请求核验，当前注册仍保持旧版。 */
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
        return "stk_suspend";
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
