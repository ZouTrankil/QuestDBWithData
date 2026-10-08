package com.zoutrankil.data.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D017 source-to-physical contract for externally owned etf_factor; only isolated D017 tables are created. */
public final class EtfFactorDataset {
    public static final String ISOLATED_PREFIX = "java_d017_etf_factor_";
    /** Official fund_factor_pro row ceiling; responses reaching this bound are incomplete. */
    public static final int SOURCE_ROW_CAP = 8_000;
    public static final List<String> PRICE_FIELDS = List.of(
            "open", "high", "low", "close", "pre_close", "change", "pct_change", "vol", "amount");
    public static final List<String> FACTOR_FIELDS = List.of(
            "asi_bfq", "asit_bfq", "bbi_bfq", "bias1_bfq", "bias2_bfq", "bias3_bfq",
            "brar_ar_bfq", "brar_br_bfq", "cr_bfq", "dfma_dif_bfq", "dfma_difma_bfq",
            "dpo_bfq", "madpo_bfq", "ema_bfq_5", "ema_bfq_10", "ema_bfq_20", "ema_bfq_30",
            "ema_bfq_60", "ema_bfq_90", "ema_bfq_250", "emv_bfq", "maemv_bfq", "expma_12_bfq",
            "expma_50_bfq", "ktn_down_bfq", "ktn_mid_bfq", "ktn_upper_bfq", "ma_bfq_5", "ma_bfq_10",
            "ma_bfq_20", "ma_bfq_30", "ma_bfq_60", "ma_bfq_90", "ma_bfq_250", "macd_bfq",
            "macd_dif_bfq", "macd_dea_bfq", "kdj_bfq", "kdj_k_bfq", "kdj_d_bfq", "rsi_bfq_6",
            "rsi_bfq_12", "rsi_bfq_24", "boll_upper_bfq", "boll_mid_bfq", "boll_lower_bfq", "atr_bfq",
            "cci_bfq", "dmi_pdi_bfq", "dmi_mdi_bfq", "dmi_adx_bfq", "dmi_adxr_bfq", "mass_bfq",
            "ma_mass_bfq", "mfi_bfq", "mtm_bfq", "mtmma_bfq", "obv_bfq", "psy_bfq", "psyma_bfq",
            "roc_bfq", "maroc_bfq", "taq_down_bfq", "taq_mid_bfq", "taq_up_bfq", "trix_bfq",
            "trma_bfq", "vr_bfq", "wr_bfq", "wr1_bfq", "xsii_td1_bfq", "xsii_td2_bfq",
            "xsii_td3_bfq", "xsii_td4_bfq", "updays", "downdays", "lowdays", "topdays");
    /** Every numeric domain/physical column, including the nine market and volume values. */
    public static final List<String> NUMERIC_FIELDS = numericFields();
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Tushare trade_date carried as UTC-midnight QuestDB TIMESTAMP; this is a calendar date, not an instant");
    private EtfFactorDataset() {}
    public static final DatasetDefinition DEFINITION = definition("etf_factor");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        var columns = new ArrayList<Column>();
        columns.add(new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                "Unmodified exchange-qualified Tushare fund code", null));
        columns.add(new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                "Provider daily trade date stored as UTC-midnight calendar carrier", TRADE_DATE));
        for (String field : PRICE_FIELDS)
            columns.add(new Column(field, field, field, StorageType.DOUBLE, true,
                    meaning(field), null));
        for (String field : FACTOR_FIELDS)
            columns.add(new Column(field, field, field, StorageType.DOUBLE, true,
                    meaning(field), null));
        return new DatasetDefinition("etf_factor", 1, "tushare.fund_factor_pro", "etf_factor_owner", table,
                ObjectKind.TABLE, columns, List.of("ts_code", "trade_date"), List.of("ts_code", "trade_date"),
                "trade_date", Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("exchange_calendar"),
                "Preserve audited YEAR/WAL/DEDUP (ts_code,trade_date). All prices, volumes, amounts and _bfq indicators remain raw source DOUBLE values; no scaling or adjustment-version substitution. The formal object is external; DDL is restricted to D017-prefixed acceptance tables.");
    }

    public static List<String> sourceFields() {
        var result = new ArrayList<String>();
        result.add("ts_code"); result.add("trade_date");
        result.addAll(NUMERIC_FIELDS);
        return List.copyOf(result);
    }
    private static List<String> numericFields() {
        var fields = new ArrayList<String>(PRICE_FIELDS);
        fields.addAll(FACTOR_FIELDS);
        if (fields.size() != 87 || fields.stream().distinct().count() != 87)
            throw new ExceptionInInitializerError("etf_factor requires exactly 87 unique numeric fields");
        return List.copyOf(fields);
    }
    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalStateException("D017 execution requires java_d017_etf_factor_<suffix>");
    }
    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        var columns = DEFINITION.columns().stream().map(c -> "\"" + c.storageName() + "\" " + c.storageType().name()).toList();
        return "CREATE TABLE " + table + " (" + String.join(", ", columns)
                + ") TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)";
    }
    private static String meaning(String field) {
        if (field.equals("vol")) return "Source fund volume in lots (手), no scaling";
        if (field.equals("amount")) return "Source fund amount in thousand yuan (千元), no scaling";
        if (field.equals("pct_change")) return "Unadjusted percent change in source percentage units; no fraction/percent conversion";
        if (List.of("open", "high", "low", "close", "pre_close", "change").contains(field))
            return "Tushare fund_factor_pro unadjusted daily price/change value, preserved without scaling";
        if (List.of("updays", "downdays", "lowdays", "topdays").contains(field))
            return "Tushare fund_factor_pro source-provided consecutive/range day count, stored as DOUBLE without recomputation";
        if (field.contains("_bfq"))
            return "Tushare fund_factor_pro indicator with _bfq (不复权) semantics, including any period suffix; source-defined parameters and raw DOUBLE are preserved without recomputation";
        return "Tushare fund_factor_pro source-defined raw DOUBLE value, no scaling";
    }

    /** Admits the existing formal table or the original isolated execution namespace. */
    public static void requireExecutionTable(String table) {
        if (!"etf_factor".equals(table)) requireIsolatedTable(table);
    }
}
