package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.JobRef;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical D012 job definition; the owner bean is StockStDailyJobService. */
public final class StockStDailySyncJobOwner {
    private StockStDailySyncJobOwner() {}

    public static final int MAX_WINDOW_DAYS = 366;

    /** Re-read the last 30 calendar days to capture namechange interval revisions. */
    public static final int REVISION_DAYS = 30;

    public static final SyncJobDefinition DEFINITION =
            new SyncJobDefinition(
                    "data.stk_st_daily",
                    1,
                    "stk_st_daily",
                    1,
                    "stk_st_daily_owner",
                    Set.of(Mode.INCREMENTAL, Mode.BACKFILL),
                    Mode.INCREMENTAL,
                    Map.of(
                            "targetId",
                            new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                            "physicalTargetId",
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
                    "stk_st_daily.namechange_year_trade_dates",
                    "questdb.full_key_values",
                    new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofHours(2)),
                    Duration.ofHours(12),
                    new Budget(
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS,
                            MAX_WINDOW_DAYS,
                            StockStDailySource.MAX_OUTPUT_ROWS,
                            1024 * 1024),
                    REVISION_DAYS,
                    List.of(
                            new JobRef("data.exchange_calendar", 1),
                            new JobRef("data.stock_detail_info", 1)),
                    Frequency.DAILY,
                    ZoneId.of("Asia/Shanghai"),
                    true,
                    true);

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
}
