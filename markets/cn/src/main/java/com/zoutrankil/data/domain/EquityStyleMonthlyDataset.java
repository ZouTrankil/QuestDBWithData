package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Explicit D103 publication contract; generated storage projections remain independent. */
public final class EquityStyleMonthlyDataset {
    private EquityStyleMonthlyDataset() {}
    public static final List<String> STORAGE_COLUMNS = List.of("month",
            "hs300_ret_1m",
            "zz500_ret_1m",
            "all_a_ret_1m",
            "cs1000_ret_1m",
            "small_large_ret_1m",
            "mid_large_ret_1m",
            "growth_ret_1m",
            "value_ret_1m",
            "growth_value_ret_1m",
            "energy_ret_1m",
            "materials_ret_1m",
            "industrials_ret_1m",
            "consumer_discretionary_ret_1m",
            "consumer_staples_ret_1m",
            "healthcare_ret_1m",
            "financials_ret_1m",
            "it_ret_1m",
            "telecom_ret_1m",
            "utilities_ret_1m",
            "energy_vs_all_a_1m",
            "materials_vs_all_a_1m",
            "industrials_vs_all_a_1m",
            "consumer_discretionary_vs_all_a_1m",
            "consumer_staples_vs_all_a_1m",
            "healthcare_vs_all_a_1m",
            "financials_vs_all_a_1m",
            "it_vs_all_a_1m",
            "telecom_vs_all_a_1m",
            "utilities_vs_all_a_1m");
    public static final DatasetDefinition DEFINITION = definition("equity_style_monthly");
    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("equity_style_monthly", 1, "derived.index_monthly.pct_chg",
                "java.equity_style_monthly.bounded_materializer", table, ObjectKind.TABLE, columns(),
                List.of("month"), List.of("month"), "month", Partition.YEAR, true,
                Set.of(Capability.READ, Capability.WRITE), List.of("index_monthly"),
                "YYYYMM period key carried at the first calendar day at exact UTC midnight; not a trading-month end or publication instant. "
                + "16 source pct_chg returns retain stored units; 13 differences use the same stored units. "
                + "Legacy value=000920.SH/growth=000921.SH bindings and growth-value formula are preserved despite index-universe naming conflict. "
                + "29 nullable finite DOUBLEs preserve raw values, signed zero and nulls without scaling or rounding. "
                + "Writes use only the isolated java_d103_equity_style_monthly_<suffix> target through the canonical bounded Java job; "
                + "no formal writer or replacement capability is admitted. Read windows and batches are bounded to 12 months. "
                + "Source readiness, point-in-time availability, full history and consumer cutover are not implied.");
    }
    public static List<Column> columns() {
        return List.of(new Column("index_monthly.trade_date:YYYYMM", "month", "month", StorageType.TIMESTAMP, false,
                "Monthly period identity; exact first calendar day UTC carrier", new TemporalContract(
                        TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "MONTH", "Original source YYYYMM becomes first-day LocalDate; period label, not event/publication time")),
                new Column("index_monthly.pct_chg[ts_code=000300.SH]:last_non_null_in_month", "hs300_ret_1m", "hs300_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000905.SH]:last_non_null_in_month", "zz500_ret_1m", "zz500_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000985.SH]:last_non_null_in_month", "all_a_ret_1m", "all_a_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000852.SH]:last_non_null_in_month", "cs1000_ret_1m", "cs1000_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("derived:cs1000_ret_1m-hs300_ret_1m", "small_large_ret_1m", "small_large_ret_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("derived:zz500_ret_1m-hs300_ret_1m", "mid_large_ret_1m", "mid_large_ret_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("index_monthly.pct_chg[ts_code=000921.SH]:last_non_null_in_month", "growth_ret_1m", "growth_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000920.SH]:last_non_null_in_month", "value_ret_1m", "value_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("derived:growth_ret_1m-value_ret_1m", "growth_value_ret_1m", "growth_value_ret_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("index_monthly.pct_chg[ts_code=000986.SH]:last_non_null_in_month", "energy_ret_1m", "energy_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000987.SH]:last_non_null_in_month", "materials_ret_1m", "materials_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000988.SH]:last_non_null_in_month", "industrials_ret_1m", "industrials_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000989.SH]:last_non_null_in_month", "consumer_discretionary_ret_1m", "consumer_discretionary_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000990.SH]:last_non_null_in_month", "consumer_staples_ret_1m", "consumer_staples_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000991.SH]:last_non_null_in_month", "healthcare_ret_1m", "healthcare_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000992.SH]:last_non_null_in_month", "financials_ret_1m", "financials_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000993.SH]:last_non_null_in_month", "it_ret_1m", "it_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000994.SH]:last_non_null_in_month", "telecom_ret_1m", "telecom_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("index_monthly.pct_chg[ts_code=000995.SH]:last_non_null_in_month", "utilities_ret_1m", "utilities_ret_1m", StorageType.DOUBLE, true,
                        "Unconverted stored index_monthly.pct_chg; last non-null dated source value", null),
                new Column("derived:energy_ret_1m-all_a_ret_1m", "energy_vs_all_a_1m", "energy_vs_all_a_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("derived:materials_ret_1m-all_a_ret_1m", "materials_vs_all_a_1m", "materials_vs_all_a_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("derived:industrials_ret_1m-all_a_ret_1m", "industrials_vs_all_a_1m", "industrials_vs_all_a_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("derived:consumer_discretionary_ret_1m-all_a_ret_1m", "consumer_discretionary_vs_all_a_1m", "consumer_discretionary_vs_all_a_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("derived:consumer_staples_ret_1m-all_a_ret_1m", "consumer_staples_vs_all_a_1m", "consumer_staples_vs_all_a_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("derived:healthcare_ret_1m-all_a_ret_1m", "healthcare_vs_all_a_1m", "healthcare_vs_all_a_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("derived:financials_ret_1m-all_a_ret_1m", "financials_vs_all_a_1m", "financials_vs_all_a_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("derived:it_ret_1m-all_a_ret_1m", "it_vs_all_a_1m", "it_vs_all_a_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("derived:telecom_ret_1m-all_a_ret_1m", "telecom_vs_all_a_1m", "telecom_vs_all_a_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null),
                new Column("derived:utilities_ret_1m-all_a_ret_1m", "utilities_vs_all_a_1m", "utilities_vs_all_a_1m", StorageType.DOUBLE, true,
                        "Difference in the same stored source-return units; preserve nullable arithmetic", null));
    }
}

