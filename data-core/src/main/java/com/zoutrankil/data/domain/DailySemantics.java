package com.zoutrankil.data.domain;

import java.util.List;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Shared daily field meanings; each runtime supplies its own physical and collection policy. */
public final class DailySemantics {
    private DailySemantics() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Tushare trade_date; UTC-midnight storage carrier is not an event instant");

    public static final DatasetSemantics V1 = new DatasetSemantics("daily", 1,
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
            List.of("ts_code", "trade_date"), "trade_date");

    private static Column metric(String name, String meaning) {
        return new Column(name, name, name, StorageType.DOUBLE, true, meaning, null);
    }
}
