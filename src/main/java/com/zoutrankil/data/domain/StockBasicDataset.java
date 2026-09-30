package com.zoutrankil.data.domain;

import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Existing Java sample only; not the Python stock_detail_info contract. */
public final class StockBasicDataset {
    private StockBasicDataset() {}
    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "stock_basic_snapshot", 1, "tushare.stock_basic", "java_stock_basic_sample",
            "java_tushare_stock_basic_qwp_test", ObjectKind.TABLE,
            List.of(new Column("derived:logical_date", "snapshot_ts", "snapshot_ts", StorageType.TIMESTAMP,
                            false, "Logical date encoded at UTC midnight; not an event time or local midnight",
                            new TemporalContract(TemporalKind.INSTANT, "ISO_OFFSET_DATE_TIME", "UTC", "MICROS", "run-day snapshot")),
                    column("ts_code", StorageType.SYMBOL, false, "Tushare instrument code"),
                    column("symbol", StorageType.SYMBOL, true, "Exchange code"),
                    column("name", StorageType.STRING, true, "Instrument name"),
                    column("area", StorageType.SYMBOL, true, "Registered area"),
                    column("industry", StorageType.SYMBOL, true, "Source industry classification"),
                    new Column("list_date", "list_date", "list_date", StorageType.STRING, true, "Listing calendar date",
                            new TemporalContract(TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "listing date"))),
            List.of("snapshot_ts", "ts_code"), List.of("snapshot_ts", "ts_code"), "snapshot_ts", Partition.DAY, true,
            Set.of(Capability.READ, Capability.WRITE), List.of(),
            "Existing V1 sample DDL: logical date as UTC midnight marker, full identity snapshot_ts + ts_code; latest view serves reads");

    public static final DatasetDefinition LATEST = new DatasetDefinition(
            "stock_basic_latest", 1, DEFINITION.provider(), DEFINITION.owner(),
            "java_tushare_stock_basic_latest_qwp_test", ObjectKind.VIEW, DEFINITION.columns(),
            List.of("ts_code"), List.of(), null, Partition.NONE, false, Set.of(Capability.READ),
            List.of(DEFINITION.datasetId()), "Existing V2 latest-by-ts_code view; no direct writes or partition");

    private static Column column(String name, StorageType type, boolean nullable, String meaning) {
        return new Column(name, name, name, type, nullable, meaning, null);
    }
}
