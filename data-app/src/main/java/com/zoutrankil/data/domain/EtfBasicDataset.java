package com.zoutrankil.data.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D013 full current-state contract for fund_basic(market=E). */
public final class EtfBasicDataset {
    public static final String ISOLATED_PREFIX = "java_d013_etf_basic_";
    private static final TemporalContract EPOCH_MARKER = new TemporalContract(
            TemporalKind.TECHNICAL, "ISO_INSTANT", "UTC", "MICROS",
            "Fixed 1970-01-01 carrier for QuestDB designated timestamp/dedup; not a source or business date");
    private static final TemporalContract OBSERVATION = new TemporalContract(
            TemporalKind.INSTANT, "ISO_INSTANT", "UTC", "MICROS",
            "Frozen Java source-observation time; fund_basic has no provider update_time field");
    private static final TemporalContract SOURCE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Provider calendar date YYYYMMDD; no timezone");
    // DatasetDefinition rejects blank legacy markers; empty source dates are normalized by the mapper.
    private static final Set<String> NULL_DATE_SENTINELS = Set.of("Null", "null");

    private EtfBasicDataset() {}

    public static final DatasetDefinition DEFINITION = definition("etf_basic");

    private static Column text(String name, StorageType type, boolean nullable, String meaning) {
        return new Column(name, name, name, type, nullable, meaning, null);
    }
    private static Column numeric(String name, String meaning) {
        return new Column(name, name, name, StorageType.DOUBLE, true, meaning + "; raw provider Double, no rescaling", null);
    }
    private static Column date(String name, String meaning) {
        return new Column(name, name, name, StorageType.STRING, true, meaning,
                SOURCE_DATE, NULL_DATE_SENTINELS);
    }

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("etf_basic", 1, "tushare.fund_basic", "EtfBasicJobService", table,
                ObjectKind.TABLE, List.of(
                text("ts_code", StorageType.SYMBOL, false, "Complete provider fund identity, including exchange suffix"),
                text("name", StorageType.STRING, true, "Provider short fund name"),
                text("management", StorageType.STRING, true, "Provider manager name"),
                text("custodian", StorageType.STRING, true, "Provider custodian name"),
                text("fund_type", StorageType.STRING, true, "Provider investment/fund classification"),
                date("found_date", "Fund establishment calendar date"),
                date("due_date", "Fund maturity calendar date"),
                date("list_date", "Listing calendar date"),
                date("issue_date", "Issue calendar date"),
                date("delist_date", "Delisting calendar date"),
                numeric("issue_amount", "Issue amount; source model describes units of 100 million shares"),
                numeric("m_fee", "Provider management fee"),
                numeric("c_fee", "Provider custodian fee"),
                numeric("duration_year", "Provider fund duration in years"),
                numeric("p_value", "Provider par value"),
                numeric("min_amount", "Minimum purchase amount; source model describes units of 10 thousand yuan"),
                numeric("exp_return", "Provider expected return; original value retained"),
                text("benchmark", StorageType.STRING, true, "Provider benchmark description"),
                text("status", StorageType.SYMBOL, true, "Provider D/I/L lifecycle status; null is preserved"),
                text("invest_type", StorageType.STRING, true, "Provider investment style"),
                text("type", StorageType.STRING, true, "Provider fund category"),
                text("trustee", StorageType.STRING, true, "Provider trustee name"),
                date("purc_startdate", "Daily subscription start calendar date"),
                date("redm_startdate", "Daily redemption start calendar date"),
                text("market", StorageType.SYMBOL, false, "Frozen Tushare fund_basic market=E request scope"),
                new Column("derived:fixed-unix-epoch", "timestamp", "timestamp", StorageType.TIMESTAMP, false,
                        "Technical 1970 epoch carrier for designated timestamp and frozen physical dedup key", EPOCH_MARKER),
                new Column("derived:frozen-observation-time", "update_time", "update_time", StorageType.TIMESTAMP, false,
                        "Java snapshot observation time frozen in the run request; not a provider field/version", OBSERVATION)),
                List.of("ts_code"), List.of("ts_code", "timestamp"), "timestamp", Partition.YEAR, true,
                Set.of(Capability.READ, Capability.WRITE), List.of(),
                "Audited target is YEAR/WAL/DEDUP on (ts_code,timestamp). timestamp is always 1970 and is not a business version; source reconciliation overwrites the one current row per ts_code. No Flyway DDL changes the externally owned table; acceptance DDL is D013-isolated only.");
    }

    public static String createIsolatedTableSql(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalArgumentException("D013 isolated table name required");
        var columns = DEFINITION.columns().stream()
                .map(column -> column.storageName() + " " + column.storageType().name()).toList();
        return "CREATE TABLE " + table + " (" + String.join(", ", columns)
                + ") TIMESTAMP(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, timestamp)";
    }

    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalStateException("D013 execution requires java_d013_etf_basic_<suffix>");
    }
    public static Instant technicalTimestamp() { return Instant.EPOCH; }
}
