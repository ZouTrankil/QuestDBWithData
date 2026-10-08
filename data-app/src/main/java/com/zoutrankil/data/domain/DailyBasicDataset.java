package com.zoutrankil.data.domain;

import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D008 source-to-storage contract for the externally owned daily_basic table. */
public final class DailyBasicDataset {
    private DailyBasicDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Tushare A-share exchange trade date");
    public static final DatasetDefinition DEFINITION = definition("daily_basic");

    /** Same frozen mapping with an explicitly owned isolated target name for acceptance runs. */
    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("daily_basic", 1, "tushare.daily_basic", "daily_basic_owner", table,
                ObjectKind.TABLE, columns(), List.of("ts_code", "trade_date"), List.of("ts_code", "trade_date"),
                "trade_date", Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("exchange_calendar"),
                "External audited YEAR/WAL table; full ts_code + trade_date key is retained; numeric values are stored in source units");
    }

    public static List<Column> columns() {
        return List.of(
                col("ts_code", StorageType.SYMBOL, false, "Tushare instrument code"),
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Exchange business date stored as UTC midnight carrier", TRADE_DATE),
                col("close", StorageType.DOUBLE, true, "Tushare daily_basic closing price, source currency per share"),
                col("turnover_rate", StorageType.DOUBLE, true, "Turnover rate percent; copied without unit conversion"),
                col("turnover_rate_f", StorageType.DOUBLE, true, "Free-float turnover rate percent; copied without unit conversion"),
                col("volume_ratio", StorageType.DOUBLE, true, "Tushare volume ratio"),
                col("pe", StorageType.DOUBLE, true, "Price to earnings ratio; null when source has no value"),
                col("pe_ttm", StorageType.DOUBLE, true, "Trailing twelve month price to earnings ratio"),
                col("pb", StorageType.DOUBLE, true, "Price to book ratio"),
                col("ps", StorageType.DOUBLE, true, "Price to sales ratio"),
                col("ps_ttm", StorageType.DOUBLE, true, "Trailing twelve month price to sales ratio"),
                col("dv_ratio", StorageType.DOUBLE, true, "Dividend yield percent; copied without unit conversion"),
                col("dv_ttm", StorageType.DOUBLE, true, "Trailing twelve month dividend yield percent"),
                col("total_share", StorageType.DOUBLE, true, "Total share capital, 10,000 shares"),
                col("float_share", StorageType.DOUBLE, true, "Tradable share capital, 10,000 shares"),
                col("free_share", StorageType.DOUBLE, true, "Free-float share capital, 10,000 shares"),
                col("total_mv", StorageType.DOUBLE, true, "Total market value, 10,000 CNY"),
                col("circ_mv", StorageType.DOUBLE, true, "Circulating market value, 10,000 CNY"));
    }

    /** DDL for a test-owned isolated table; formal-table migration remains with its external owner. */
    public static String createTableSql(String table) {
        DatasetDefinition.identifier(table);
        var definitions = new ArrayList<String>();
        for (var column : columns()) {
            definitions.add(column.storageName() + " " + column.storageType().name());
        }
        return "CREATE TABLE " + table + " (" + String.join(", ", definitions)
                + ") TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)";
    }

    private static Column col(String name, StorageType type, boolean nullable, String meaning) {
        return new Column(name, name, name, type, nullable, meaning, null);
    }
}
