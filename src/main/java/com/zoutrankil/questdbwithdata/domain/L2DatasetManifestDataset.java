package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** Frozen D085 schema and source semantics for `l2_dataset_manifest`. */
public final class L2DatasetManifestDataset {
    private L2DatasetManifestDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "L2 exchange business date read from the Parquet trade_date partition");
    private static final TemporalContract PARTITION_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Synthetic UTC-midnight QuestDB partition carrier derived one-to-one from trade_date");

    public static final DatasetDefinition DEFINITION = definition("l2_dataset_manifest");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition(
            "l2_dataset_manifest", 1, "local.level2.parquet", "l2_dataset_manifest_owner",
            table, ObjectKind.TABLE,
            List.of(
                    date("trade_date", "L2 exchange business date in YYYYMMDD"),
                    new Column("symbol", "symbol", "symbol", StorageType.SYMBOL, false,
                            "Canonical six-digit stock code with exchange suffix", null),
                    text("market", "Upstream market category derived from the stock symbol"),
                    text("board", "Upstream board category derived from the stock symbol"),
                    text("source_root", "Exact source archive root recorded by the L2 producer"),
                    text("output_root", "Exact feature output root recorded by the L2 producer"),
                    text("feature_version", "L2 parser/feature version emitted by the producer"),
                    new Column("daily_feature_ok", "daily_feature_ok", "daily_feature_ok", StorageType.BOOLEAN,
                            true, "Producer daily-feature quality result; nullable values remain unknown", null),
                    new Column("t0_ok", "t0_ok", "t0_ok", StorageType.BOOLEAN,
                            true, "Producer T0 quality result; nullable values remain unknown", null),
                    text("raw_row_counts", "Producer JSON text with deal/order/snapshot source counts"),
                    text("output_paths", "Producer JSON text with per-product output paths"),
                    text("cost_config", "Producer JSON text with frozen transaction-cost settings"),
                    text("horizons_min", "Producer JSON text with configured label horizons in minutes"),
                    text("errors", "Producer JSON text with row-level processing errors"),
                    new Column("batch_id", "batch_id", "batch_id", StorageType.LONG, false,
                            "Source processing batch encoded in the Parquet part filename", null),
                    new Column("derived:trade_date@UTC-midnight", "trade_date_ts", "trade_date_ts",
                            StorageType.TIMESTAMP, false,
                            "Technical DAY partition carrier; derived from trade_date, not an event instant",
                            PARTITION_DATE)),
            List.of("trade_date_ts", "symbol", "batch_id"),
            List.of("symbol", "batch_id", "trade_date_ts"), "trade_date_ts", Partition.DAY, true,
            Set.of(Capability.READ, Capability.WRITE), List.of("exchange_calendar"),
            "Keep the audited DAY/WAL/DEDUP layout and (symbol,batch_id,trade_date_ts) UPSERT KEY. "
                    + "The business identity is (trade_date,symbol,batch_id), represented by the one-to-one "
                    + "trade_date_ts calendar carrier plus symbol and batch_id; the highest batch_id per "
                    + "symbol/day is the Python consumer selection rule, while every source batch remains stored. "
                    + "The physical timestamp is a UTC-midnight carrier derived from the calendar date.");
    }

    private static Column date(String name, String meaning) {
        return new Column(name, name, name, StorageType.STRING, false, meaning, TRADE_DATE);
    }

    private static Column text(String name, String meaning) {
        return new Column(name, name, name, StorageType.STRING, true, meaning, null);
    }
}
