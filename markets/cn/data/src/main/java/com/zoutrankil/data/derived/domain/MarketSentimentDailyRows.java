package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.derived.MarketSentimentDailyDataset;
import com.zoutrankil.data.domain.derived.table.MarketSentimentDailyRow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class MarketSentimentDailyRows {
    private MarketSentimentDailyRows() {}

    public static final List<String> COLUMNS = MarketSentimentDailyDataset.COLUMNS;
    public static final String FACTOR_SOURCE_ENDPOINT = "stk_factor";
    private static final NativeDailyProjection<MarketSentimentDailyRow> PROJECTION =
            new NativeDailyProjection<>(MarketSentimentDailyRow.class, COLUMNS);

    public static NativeDailyProjection<MarketSentimentDailyRow> projection() {
        return PROJECTION;
    }

    public static void requireTarget(String table) {
        NativeDailyWindowRules.requireTarget(
                table, "market_sentiment_daily", "java_d121_market_sentiment_daily");
    }

    public static boolean outside(MarketSentimentDailyRow row, LocalDate from, LocalDate to) {
        return PROJECTION.outside(row, from, to);
    }

    public static MarketSentimentDailyRow row(Map<String, ?> map) {
        return PROJECTION.row(map);
    }

    public static Map<String, Object> values(MarketSentimentDailyRow row) {
        return PROJECTION.values(row);
    }

    public static long micros(Instant time) {
        return NativeDailyWindowRules.micros(time);
    }

    public static Instant fromMicros(long time) {
        return NativeDailyWindowRules.fromMicros(time);
    }

    public static String quotedColumns() {
        return PROJECTION.quotedColumns();
    }

    public static String digest(List<MarketSentimentDailyRow> rows) {
        return PROJECTION.digest(rows);
    }
}
