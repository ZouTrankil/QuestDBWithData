package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.SyncJobDefinition;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Canonical D012 job definition; the owner bean is StockStDailyJobService. */
public final class StockStDailySyncJobOwner {
    private StockStDailySyncJobOwner() {}
    public static final int MAX_WINDOW_DAYS = 366;
    /** Re-read the last 30 calendar days to capture namechange interval revisions. */
    public static final int REVISION_DAYS = 30;
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.stk_st_daily", 1, "stk_st_daily", 1, "stk_st_daily_owner",
            Set.of(Mode.INCREMENTAL, Mode.BACKFILL), Mode.INCREMENTAL,
            Map.of("targetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "physicalTargetId", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                    "trade_dates", new Parameter(ParameterType.STRING, true, 4000, 1, Set.of()),
                    "checkpointAnchor", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "checkpointBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMinBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()),
                    "targetMaxBefore", new Parameter(ParameterType.DATE, false, 10, 1, Set.of())),
            "tushare.shared", "stk_st_daily.namechange_year_trade_dates", "questdb.full_key_values",
            new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofHours(2)), Duration.ofHours(12),
            new Budget(MAX_WINDOW_DAYS, MAX_WINDOW_DAYS, MAX_WINDOW_DAYS,
                    StockStDailySource.MAX_OUTPUT_ROWS, 1024 * 1024), REVISION_DAYS,
            List.of(new JobRef("data.exchange_calendar", 1), new JobRef("data.stock_detail_info", 1)),
            Frequency.DAILY, ZoneId.of("Asia/Shanghai"), true, true);
}
