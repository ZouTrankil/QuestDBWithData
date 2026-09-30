package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Frozen D007 storage and source-to-domain contract for `daily`. */
public final class DailyDataset {
    private DailyDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Tushare trade_date; UTC-midnight storage carrier is not an event instant");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "daily", 1, "tushare.daily", "daily_owner", "daily", ObjectKind.TABLE,
            List.of(
                    new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                            "Tushare mainland equity code including exchange suffix", null),
                    new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                            "Exchange business date in YYYYMMDD; stored as UTC midnight carrier", TRADE_DATE),
                    metric("open", "Unadjusted open price; provider price unit"),
                    metric("high", "Unadjusted high price; provider price unit"),
                    metric("low", "Unadjusted low price; provider price unit"),
                    metric("close", "Unadjusted close price; provider price unit"),
                    metric("pre_close", "Provider previous close price; provider adjustment semantics"),
                    metric("change", "Provider daily price change; provider price unit"),
                    metric("pct_chg", "Provider percent change in percent points, not a fraction"),
                    metric("vol", "Provider volume in lots (手)"),
                    metric("amount", "Provider turnover in thousands of CNY (千元)"),
                    metric("ah_vol", "Provider after-hours volume in lots (手)"),
                    metric("ah_amount", "Provider after-hours turnover in thousands of CNY (千元)")),
            List.of("ts_code", "trade_date"), List.of("ts_code", "trade_date"), "trade_date",
            Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
            List.of("exchange_calendar", "stock_detail_info"),
            "Retain the audited YEAR/WAL/dedup table and (ts_code, trade_date) UPSERT KEY. "
                    + "Same-key provider corrections replace prior values; re-fetch a finite five-calendar-day overlap. "
                    + "No source revision timestamp is available, so revisions are not separately versioned.");

    private static Column metric(String name, String meaning) {
        return new Column(name, name, name, StorageType.DOUBLE, true, meaning, null);
    }
}
