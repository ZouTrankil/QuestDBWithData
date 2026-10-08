package com.zoutrankil.data.domain;

import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Frozen Java mapping to the existing audited Tushare stk_factor physical table. */
public final class StockFactorDataset {
    private StockFactorDataset() {}
    private static Column number(String source, String logical, String meaning) {
        return new Column(source, logical, logical, StorageType.DOUBLE, true, meaning, null);
    }
    public static final List<String> STORAGE_COLUMNS = List.of(
            "ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_change",
            "vol", "amount", "adj_factor", "open_hfq", "open_qfq", "close_hfq", "close_qfq",
            "high_hfq", "high_qfq", "low_hfq", "low_qfq", "pre_close_hfq", "pre_close_qfq",
            "macd_dif", "macd_dea", "macd", "kdj_k", "kdj_d", "kdj_j", "rsi_6", "rsi_12", "rsi_24",
            "boll_upper", "boll_mid", "boll_lower", "cci");
    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "stk_factor", 1, "tushare.stk_factor", "stk_factor_owner", "stk_factor", ObjectKind.TABLE,
            List.of(
                    new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                            "Tushare A-share identity with exchange suffix", null),
                    new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                            "Source calendar trading date stored as UTC-midnight carrier; not an event instant",
                            new TemporalContract(TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Trading date")),
                    number("close", "close", "Unadjusted close price"),
                    number("open", "open", "Unadjusted open price"),
                    number("high", "high", "Unadjusted high price"),
                    number("low", "low", "Unadjusted low price"),
                    number("pre_close", "pre_close", "Provider pre-close; provider documents its adjustment caveat"),
                    number("change", "change", "Provider change value; no rescaling"),
                    number("pct_change", "pct_change", "Legacy stk_factor percentage change; no rescaling"),
                    number("vol", "vol", "Provider volume, in lots/手"),
                    number("amount", "amount", "Provider amount, in thousands of RMB/千元"),
                    number("adj_factor", "adj_factor", "Provider adjustment factor; stored without recomputation"),
                    number("open_hfq", "open_hfq", "Provider backward-adjusted open"),
                    number("open_qfq", "open_qfq", "Provider forward-adjusted open"),
                    number("close_hfq", "close_hfq", "Provider backward-adjusted close"),
                    number("close_qfq", "close_qfq", "Provider forward-adjusted close"),
                    number("high_hfq", "high_hfq", "Provider backward-adjusted high"),
                    number("high_qfq", "high_qfq", "Provider forward-adjusted high"),
                    number("low_hfq", "low_hfq", "Provider backward-adjusted low"),
                    number("low_qfq", "low_qfq", "Provider forward-adjusted low"),
                    number("pre_close_hfq", "pre_close_hfq", "Provider backward-adjusted pre-close"),
                    number("pre_close_qfq", "pre_close_qfq", "Provider forward-adjusted pre-close"),
                    number("macd_dif", "macd_dif", "Provider MACD DIF"),
                    number("macd_dea", "macd_dea", "Provider MACD DEA"),
                    number("macd", "macd", "Provider MACD"),
                    number("kdj_k", "kdj_k", "Provider KDJ K"),
                    number("kdj_d", "kdj_d", "Provider KDJ D"),
                    number("kdj_j", "kdj_j", "Provider KDJ J"),
                    number("rsi_6", "rsi_6", "Provider six-period RSI"),
                    number("rsi_12", "rsi_12", "Provider twelve-period RSI"),
                    number("rsi_24", "rsi_24", "Provider twenty-four-period RSI"),
                    number("boll_upper", "boll_upper", "Provider BOLL upper band"),
                    number("boll_mid", "boll_mid", "Provider BOLL middle band"),
                    number("boll_lower", "boll_lower", "Provider BOLL lower band"),
                    number("cci", "cci", "Provider CCI")),
            List.of("ts_code", "trade_date"), List.of("ts_code", "trade_date"), "trade_date", Partition.YEAR, true,
            Set.of(Capability.READ, Capability.WRITE), List.of(),
            "Existing physical table is audited as YEAR-partitioned WAL with DEDUP UPSERT KEYS(ts_code,trade_date). " +
                    "No production DDL is applied by this owner; preflight rejects drift and requires the external target.");
}
