package com.zoutrankil.data.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.zoutrankil.data.domain.DatasetDefinition.StorageType;

/** Frozen D088 physical field mapping; local Parquet values normalize to the audited QuestDB types. */
public enum L2EventResponseFeatureField {
    TRADE_DATE("trade_date", StorageType.STRING, false, "A-share business date in YYYYMMDD; must equal minute interpreted in Asia/Shanghai", true),
    SYMBOL("symbol", StorageType.SYMBOL, false, "Canonical six-digit stock code with exchange suffix; one part is selected per symbol and date", false),
    MARKET("market", StorageType.STRING, false, "Market suffix derived by the Python stock symbol mapping", false),
    BOARD("board", StorageType.STRING, false, "Board category derived by the Python stock symbol mapping", false),
    MINUTE("minute", StorageType.TIMESTAMP, false, "Minute floor of source event time, naive Asia/Shanghai wall time in Parquet and UTC instant in QuestDB", true),
    OPEN("open", StorageType.DOUBLE, true, "First scaled trade price in the minute", false),
    HIGH("high", StorageType.DOUBLE, true, "Maximum scaled trade price in the minute", false),
    LOW("low", StorageType.DOUBLE, true, "Minimum scaled trade price in the minute", false),
    CLOSE("close", StorageType.DOUBLE, true, "Last scaled trade price in the minute", false),
    VOLUME("volume", StorageType.DOUBLE, false, "Sum of source trade volume in the minute; empty trade minutes are zero", false),
    AMOUNT("amount", StorageType.DOUBLE, false, "Sum of scaled trade price times volume; empty trade minutes are zero", false),
    TICK_COUNT("tick_count", StorageType.LONG, false, "Number of valid positive-price trade rows in the minute; empty trade minutes are zero", false),
    ACTIVE_BUY_AMOUNT("active_buy_amount", StorageType.DOUBLE, true, "Trade amount whose source Side equals zero", false),
    ACTIVE_SELL_AMOUNT("active_sell_amount", StorageType.DOUBLE, true, "Trade amount whose source Side equals one", false),
    VWAP("vwap", StorageType.DOUBLE, true, "Minute trade amount divided by minute volume; null when volume is zero", false),
    HAS_TRADE_1M("has_trade_1m", StorageType.LONG, false, "One when the minute has a valid trade, otherwise zero", false),
    BID1("bid1", StorageType.DOUBLE, true, "Last valid best bid price in the minute", false),
    ASK1("ask1", StorageType.DOUBLE, true, "Last valid best ask price in the minute", false),
    MID("mid", StorageType.DOUBLE, true, "Midpoint of a valid non-crossed best bid and ask", false),
    SPREAD("spread", StorageType.DOUBLE, true, "Relative best-quote spread, ask minus bid divided by midpoint", false),
    MICROPRICE("microprice", StorageType.DOUBLE, true, "Best-quote size-weighted midpoint", false),
    BID_DEPTH_1("bid_depth_1", StorageType.DOUBLE, true, "Sum of bid sizes from levels 1 through 1 in the last quote of the minute", false),
    ASK_DEPTH_1("ask_depth_1", StorageType.DOUBLE, true, "Sum of ask sizes from levels 1 through 1 in the last quote of the minute", false),
    DEPTH_1("depth_1", StorageType.DOUBLE, true, "Bid depth plus ask depth for the first 1 quote levels", false),
    OBI_1("obi_1", StorageType.DOUBLE, true, "(bid depth minus ask depth) divided by total depth for 1 quote levels", false),
    BID_DEPTH_5("bid_depth_5", StorageType.DOUBLE, true, "Sum of bid sizes from levels 1 through 5 in the last quote of the minute", false),
    ASK_DEPTH_5("ask_depth_5", StorageType.DOUBLE, true, "Sum of ask sizes from levels 1 through 5 in the last quote of the minute", false),
    DEPTH_5("depth_5", StorageType.DOUBLE, true, "Bid depth plus ask depth for the first 5 quote levels", false),
    OBI_5("obi_5", StorageType.DOUBLE, true, "(bid depth minus ask depth) divided by total depth for 5 quote levels", false),
    BID_DEPTH_10("bid_depth_10", StorageType.DOUBLE, true, "Sum of bid sizes from levels 1 through 10 in the last quote of the minute", false),
    ASK_DEPTH_10("ask_depth_10", StorageType.DOUBLE, true, "Sum of ask sizes from levels 1 through 10 in the last quote of the minute", false),
    DEPTH_10("depth_10", StorageType.DOUBLE, true, "Bid depth plus ask depth for the first 10 quote levels", false),
    OBI_10("obi_10", StorageType.DOUBLE, true, "(bid depth minus ask depth) divided by total depth for 10 quote levels", false),
    QUOTE_COUNT("quote_count", StorageType.DOUBLE, true, "Number of source quote snapshots grouped into the minute", false),
    OFI_1M("ofi_1m", StorageType.DOUBLE, true, "One-minute change in top-level bid depth minus change in ask depth", false),
    ACTIVE_BUY_RATIO("active_buy_ratio", StorageType.DOUBLE, true, "Active buy amount divided by minute amount", false),
    ACTIVE_SELL_RATIO("active_sell_ratio", StorageType.DOUBLE, true, "Active sell amount divided by minute amount", false),
    VWAP_GAP_TO_MID("vwap_gap_to_mid", StorageType.DOUBLE, true, "Relative minute VWAP gap from quote midpoint", false),
    VWAP_GAP_TO_OPEN("vwap_gap_to_open", StorageType.DOUBLE, true, "Relative minute VWAP gap from minute open", false),
    VWAP_SLOPE_3M("vwap_slope_3m", StorageType.DOUBLE, true, "Three-row VWAP percentage change", false),
    VWAP_SLOPE_5M("vwap_slope_5m", StorageType.DOUBLE, true, "Five-row VWAP percentage change", false),
    RET_1M("ret_1m", StorageType.DOUBLE, true, "VWAP percentage change over 1 rows", false),
    VOL_RATIO_1M("vol_ratio_1m", StorageType.DOUBLE, true, "Minute volume divided by trailing 1-row mean volume", false),
    RANGE_1M("range_1m", StorageType.DOUBLE, true, "Rolling high divided by rolling low minus one over 1 rows", false),
    RET_3M("ret_3m", StorageType.DOUBLE, true, "VWAP percentage change over 3 rows", false),
    VOL_RATIO_3M("vol_ratio_3m", StorageType.DOUBLE, true, "Minute volume divided by trailing 3-row mean volume", false),
    RANGE_3M("range_3m", StorageType.DOUBLE, true, "Rolling high divided by rolling low minus one over 3 rows", false),
    RET_5M("ret_5m", StorageType.DOUBLE, true, "VWAP percentage change over 5 rows", false),
    VOL_RATIO_5M("vol_ratio_5m", StorageType.DOUBLE, true, "Minute volume divided by trailing 5-row mean volume", false),
    RANGE_5M("range_5m", StorageType.DOUBLE, true, "Rolling high divided by rolling low minus one over 5 rows", false),
    RET_10M("ret_10m", StorageType.DOUBLE, true, "VWAP percentage change over 10 rows", false),
    VOL_RATIO_10M("vol_ratio_10m", StorageType.DOUBLE, true, "Minute volume divided by trailing 10-row mean volume", false),
    RANGE_10M("range_10m", StorageType.DOUBLE, true, "Rolling high divided by rolling low minus one over 10 rows", false),
    RET_15M("ret_15m", StorageType.DOUBLE, true, "VWAP percentage change over 15 rows", false),
    VOL_RATIO_15M("vol_ratio_15m", StorageType.DOUBLE, true, "Minute volume divided by trailing 15-row mean volume", false),
    RANGE_15M("range_15m", StorageType.DOUBLE, true, "Rolling high divided by rolling low minus one over 15 rows", false),
    RET_30M("ret_30m", StorageType.DOUBLE, true, "VWAP percentage change over 30 rows", false),
    VOL_RATIO_30M("vol_ratio_30m", StorageType.DOUBLE, true, "Minute volume divided by trailing 30-row mean volume", false),
    RANGE_30M("range_30m", StorageType.DOUBLE, true, "Rolling high divided by rolling low minus one over 30 rows", false),
    CANCEL_RATIO("cancel_ratio", StorageType.DOUBLE, true, "Share of minute order rows with source OrderType -1 or -11", false),
    EVENT_TYPE("event_type", StorageType.STRING, false, "Rule-generated event category; one of six source-defined event types; part of the full business and UPSERT key", false),
    FUTURE_VWAP_RETURN_1M("future_vwap_return_1m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after event minute plus 1 minute divided by event-minute VWAP minus one", false),
    FUTURE_MID_RETURN_1M("future_mid_return_1m", StorageType.DOUBLE, true, "Source-named mid outcome; actual Python formula is the same future VWAP divided by event-minute mid minus one", false),
    FUTURE_VWAP_RETURN_3M("future_vwap_return_3m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after event minute plus 3 minutes divided by event-minute VWAP minus one", false),
    FUTURE_MID_RETURN_3M("future_mid_return_3m", StorageType.DOUBLE, true, "Source-named mid outcome; actual Python formula is the same future VWAP divided by event-minute mid minus one", false),
    FUTURE_VWAP_RETURN_5M("future_vwap_return_5m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after event minute plus 5 minutes divided by event-minute VWAP minus one", false),
    FUTURE_MID_RETURN_5M("future_mid_return_5m", StorageType.DOUBLE, true, "Source-named mid outcome; actual Python formula is the same future VWAP divided by event-minute mid minus one", false),
    FUTURE_VWAP_RETURN_10M("future_vwap_return_10m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after event minute plus 10 minutes divided by event-minute VWAP minus one", false),
    FUTURE_MID_RETURN_10M("future_mid_return_10m", StorageType.DOUBLE, true, "Source-named mid outcome; actual Python formula is the same future VWAP divided by event-minute mid minus one", false),
    FUTURE_VWAP_RETURN_15M("future_vwap_return_15m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after event minute plus 15 minutes divided by event-minute VWAP minus one", false),
    FUTURE_MID_RETURN_15M("future_mid_return_15m", StorageType.DOUBLE, true, "Source-named mid outcome; actual Python formula is the same future VWAP divided by event-minute mid minus one", false),
    FUTURE_VWAP_RETURN_30M("future_vwap_return_30m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after event minute plus 30 minutes divided by event-minute VWAP minus one", false),
    FUTURE_MID_RETURN_30M("future_mid_return_30m", StorageType.DOUBLE, true, "Source-named mid outcome; actual Python formula is the same future VWAP divided by event-minute mid minus one", false);

    public static final java.util.Set<String> EVENT_TYPE_ALLOWED = java.util.Set.of(
            "large_active_buy", "large_active_sell", "large_turnover", "bid_depth_depletion",
            "ask_depth_depletion", "high_cancel_ratio");

    private static final Map<String, L2EventResponseFeatureField> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(L2EventResponseFeatureField::fieldName, Function.identity()));
    private final String fieldName;
    private final StorageType storageType;
    private final boolean nullable;
    private final String meaning;
    private final boolean temporal;

    L2EventResponseFeatureField(String fieldName, StorageType storageType, boolean nullable, String meaning, boolean temporal) {
        this.fieldName = fieldName;
        this.storageType = storageType;
        this.nullable = nullable;
        this.meaning = meaning;
        this.temporal = temporal;
    }

    public String fieldName() { return fieldName; }
    public StorageType storageType() { return storageType; }
    public boolean nullable() { return nullable; }
    public String meaning() { return meaning; }
    public boolean temporal() { return temporal; }
    public boolean metric() { return switch (this) {
        case TRADE_DATE, SYMBOL, MARKET, BOARD, MINUTE, EVENT_TYPE -> false;
        default -> true;
    }; }
    public boolean key() { return this == SYMBOL || this == MINUTE || this == EVENT_TYPE; }
    public static L2EventResponseFeatureField named(String name) { return BY_NAME.get(name); }

    public Object decode(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            if (!nullable) throw new IOException("Required D088 field is null: " + fieldName);
            return null;
        }
        return switch (storageType) {
            case LONG -> {
                if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IOException("D088 LONG type mismatch: " + fieldName);
                yield node.longValue();
            }
            case DOUBLE -> {
                if (!node.isNumber()) throw new IOException("D088 DOUBLE type mismatch: " + fieldName);
                double value = node.doubleValue();
                if (!Double.isFinite(value)) throw new IOException("D088 DOUBLE is non-finite: " + fieldName);
                yield value;
            }
            case STRING, SYMBOL -> {
                if (!node.isTextual()) throw new IOException("D088 text type mismatch: " + fieldName);
                yield node.textValue();
            }
            case TIMESTAMP -> throw new IOException("D088 minute is decoded as Shanghai local wall time");
            default -> throw new IOException("Unsupported D088 storage type: " + fieldName);
        };
    }

    public Object normalize(Object value) {
        if (value == null) {
            if (!nullable) throw new IllegalArgumentException("Required D088 field is null: " + fieldName);
            return null;
        }
        return switch (storageType) {
            case LONG -> {
                if (!(value instanceof Number number)) throw new IllegalArgumentException("D088 LONG type mismatch: " + fieldName);
                try { yield new BigDecimal(number.toString()).longValueExact(); }
                catch (ArithmeticException | NumberFormatException failure) { throw new IllegalArgumentException("D088 LONG is not exact: " + fieldName, failure); }
            }
            case DOUBLE -> {
                if (!(value instanceof Number number)) throw new IllegalArgumentException("D088 DOUBLE type mismatch: " + fieldName);
                double normalized = number.doubleValue();
                if (Double.isNaN(normalized) && nullable) yield null;
                if (!Double.isFinite(normalized)) throw new IllegalArgumentException("D088 DOUBLE is non-finite: " + fieldName);
                yield normalized;
            }
            case STRING, SYMBOL -> {
                if (!(value instanceof String)) throw new IllegalArgumentException("D088 text type mismatch: " + fieldName);
                yield value;
            }
            case TIMESTAMP -> throw new IllegalArgumentException("D088 timestamp is normalized from local Parquet wall time");
            default -> throw new IllegalArgumentException("Unsupported D088 storage type: " + fieldName);
        };
    }
}
