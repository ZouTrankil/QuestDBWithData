package com.zoutrankil.data.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.zoutrankil.data.domain.DatasetDefinition.StorageType;

/** Explicit 62-column D089 source-to-QuestDB field contract. */
public enum L2T0TrainingLabelField {
    TRADE_DATE("trade_date", StorageType.STRING, false, "A-share business date YYYYMMDD; equals minute interpreted in Asia/Shanghai", true),
    SYMBOL("symbol", StorageType.SYMBOL, false, "Canonical six-digit stock code with exchange suffix and part of the complete key", false),
    MARKET("market", StorageType.STRING, false, "Market suffix derived by the Python stock symbol mapping", false),
    BOARD("board", StorageType.STRING, false, "Board category derived by the Python stock symbol mapping", false),
    MINUTE("minute", StorageType.TIMESTAMP, false, "Minute wall time in Asia/Shanghai in Parquet; stored as a UTC instant", true),
    EXECUTABILITY_LABEL("executability_label", StorageType.LONG, false, "1 when spread, top-one depth, and entry bid/ask meet the Python execution rule; otherwise 0", false),
    EXECUTABILITY_REASON("executability_reason", StorageType.STRING, false, "Python execution rule reason: quote_depth_ok or wide_spread_or_thin_depth", false),
    POLICY_LABEL("policy_label", StorageType.STRING, false, "Python placeholder rule_data_missing; not an executable policy decision", false),
    POLICY_REASON("policy_reason", StorageType.STRING, false, "Python missing-input reason for inventory, limits, and price-cage data", false),
    PRIMARY_T0_SIDE("primary_t0_side", StorageType.STRING, false, "Python placeholder sell_first_inventory_required; inventory is not present here", false),
    ROUNDTRIP_COST_RATE("roundtrip_cost_rate", StorageType.DOUBLE, false, "Two commissions plus stamp tax plus slippage_bps divided by 10000", false),
    STAMP_TAX_RATE("stamp_tax_rate", StorageType.DOUBLE, false, "Python-configured stock stamp-tax rate used in round-trip cost", false),
    COMMISSION_RATE("commission_rate", StorageType.DOUBLE, false, "Python-configured one-way commission rate", false),
    SLIPPAGE_BPS("slippage_bps", StorageType.DOUBLE, false, "Python-configured round-trip slippage in basis points", false),
    FUTURE_VWAP_RETURN_1M("future_vwap_return_1m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after minute plus 1m divided by event-minute VWAP minus one; null when unavailable", false),
    FUTURE_MID_RETURN_1M("future_mid_return_1m", StorageType.DOUBLE, true, "Look-ahead outcome: source formula uses future VWAP divided by event-minute mid minus one; null when unavailable", false),
    SELL_FIRST_GROSS_ALPHA_1M("sell_first_gross_alpha_1m", StorageType.DOUBLE, true, "Look-ahead sell-first gross alpha at 1m: entry bid (falling back to entry VWAP) divided by future ask (falling back to future VWAP) minus one", false),
    SELL_FIRST_NET_ALPHA_1M("sell_first_net_alpha_1m", StorageType.DOUBLE, true, "Sell-first gross alpha at 1m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    SELL_FIRST_OPPORTUNITY_LABEL_1M("sell_first_opportunity_label_1m", StorageType.LONG, false, "1 when sell-first gross alpha is strictly above the configured threshold at 1m; source maps missing alpha to 0", false),
    BUY_FIRST_AUX_GROSS_ALPHA_1M("buy_first_aux_gross_alpha_1m", StorageType.DOUBLE, true, "Look-ahead buy-first auxiliary gross alpha at 1m: future bid (falling back to future VWAP) divided by entry ask (falling back to entry VWAP) minus one", false),
    BUY_FIRST_AUX_NET_ALPHA_1M("buy_first_aux_net_alpha_1m", StorageType.DOUBLE, true, "Buy-first auxiliary gross alpha at 1m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    BUY_FIRST_AUX_OPPORTUNITY_LABEL_1M("buy_first_aux_opportunity_label_1m", StorageType.LONG, false, "1 when buy-first auxiliary gross alpha is strictly above the configured threshold at 1m; source maps missing alpha to 0", false),
    FUTURE_VWAP_RETURN_3M("future_vwap_return_3m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after minute plus 3m divided by event-minute VWAP minus one; null when unavailable", false),
    FUTURE_MID_RETURN_3M("future_mid_return_3m", StorageType.DOUBLE, true, "Look-ahead outcome: source formula uses future VWAP divided by event-minute mid minus one; null when unavailable", false),
    SELL_FIRST_GROSS_ALPHA_3M("sell_first_gross_alpha_3m", StorageType.DOUBLE, true, "Look-ahead sell-first gross alpha at 3m: entry bid (falling back to entry VWAP) divided by future ask (falling back to future VWAP) minus one", false),
    SELL_FIRST_NET_ALPHA_3M("sell_first_net_alpha_3m", StorageType.DOUBLE, true, "Sell-first gross alpha at 3m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    SELL_FIRST_OPPORTUNITY_LABEL_3M("sell_first_opportunity_label_3m", StorageType.LONG, false, "1 when sell-first gross alpha is strictly above the configured threshold at 3m; source maps missing alpha to 0", false),
    BUY_FIRST_AUX_GROSS_ALPHA_3M("buy_first_aux_gross_alpha_3m", StorageType.DOUBLE, true, "Look-ahead buy-first auxiliary gross alpha at 3m: future bid (falling back to future VWAP) divided by entry ask (falling back to entry VWAP) minus one", false),
    BUY_FIRST_AUX_NET_ALPHA_3M("buy_first_aux_net_alpha_3m", StorageType.DOUBLE, true, "Buy-first auxiliary gross alpha at 3m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    BUY_FIRST_AUX_OPPORTUNITY_LABEL_3M("buy_first_aux_opportunity_label_3m", StorageType.LONG, false, "1 when buy-first auxiliary gross alpha is strictly above the configured threshold at 3m; source maps missing alpha to 0", false),
    FUTURE_VWAP_RETURN_5M("future_vwap_return_5m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after minute plus 5m divided by event-minute VWAP minus one; null when unavailable", false),
    FUTURE_MID_RETURN_5M("future_mid_return_5m", StorageType.DOUBLE, true, "Look-ahead outcome: source formula uses future VWAP divided by event-minute mid minus one; null when unavailable", false),
    SELL_FIRST_GROSS_ALPHA_5M("sell_first_gross_alpha_5m", StorageType.DOUBLE, true, "Look-ahead sell-first gross alpha at 5m: entry bid (falling back to entry VWAP) divided by future ask (falling back to future VWAP) minus one", false),
    SELL_FIRST_NET_ALPHA_5M("sell_first_net_alpha_5m", StorageType.DOUBLE, true, "Sell-first gross alpha at 5m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    SELL_FIRST_OPPORTUNITY_LABEL_5M("sell_first_opportunity_label_5m", StorageType.LONG, false, "1 when sell-first gross alpha is strictly above the configured threshold at 5m; source maps missing alpha to 0", false),
    BUY_FIRST_AUX_GROSS_ALPHA_5M("buy_first_aux_gross_alpha_5m", StorageType.DOUBLE, true, "Look-ahead buy-first auxiliary gross alpha at 5m: future bid (falling back to future VWAP) divided by entry ask (falling back to entry VWAP) minus one", false),
    BUY_FIRST_AUX_NET_ALPHA_5M("buy_first_aux_net_alpha_5m", StorageType.DOUBLE, true, "Buy-first auxiliary gross alpha at 5m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    BUY_FIRST_AUX_OPPORTUNITY_LABEL_5M("buy_first_aux_opportunity_label_5m", StorageType.LONG, false, "1 when buy-first auxiliary gross alpha is strictly above the configured threshold at 5m; source maps missing alpha to 0", false),
    FUTURE_VWAP_RETURN_10M("future_vwap_return_10m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after minute plus 10m divided by event-minute VWAP minus one; null when unavailable", false),
    FUTURE_MID_RETURN_10M("future_mid_return_10m", StorageType.DOUBLE, true, "Look-ahead outcome: source formula uses future VWAP divided by event-minute mid minus one; null when unavailable", false),
    SELL_FIRST_GROSS_ALPHA_10M("sell_first_gross_alpha_10m", StorageType.DOUBLE, true, "Look-ahead sell-first gross alpha at 10m: entry bid (falling back to entry VWAP) divided by future ask (falling back to future VWAP) minus one", false),
    SELL_FIRST_NET_ALPHA_10M("sell_first_net_alpha_10m", StorageType.DOUBLE, true, "Sell-first gross alpha at 10m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    SELL_FIRST_OPPORTUNITY_LABEL_10M("sell_first_opportunity_label_10m", StorageType.LONG, false, "1 when sell-first gross alpha is strictly above the configured threshold at 10m; source maps missing alpha to 0", false),
    BUY_FIRST_AUX_GROSS_ALPHA_10M("buy_first_aux_gross_alpha_10m", StorageType.DOUBLE, true, "Look-ahead buy-first auxiliary gross alpha at 10m: future bid (falling back to future VWAP) divided by entry ask (falling back to entry VWAP) minus one", false),
    BUY_FIRST_AUX_NET_ALPHA_10M("buy_first_aux_net_alpha_10m", StorageType.DOUBLE, true, "Buy-first auxiliary gross alpha at 10m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    BUY_FIRST_AUX_OPPORTUNITY_LABEL_10M("buy_first_aux_opportunity_label_10m", StorageType.LONG, false, "1 when buy-first auxiliary gross alpha is strictly above the configured threshold at 10m; source maps missing alpha to 0", false),
    FUTURE_VWAP_RETURN_15M("future_vwap_return_15m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after minute plus 15m divided by event-minute VWAP minus one; null when unavailable", false),
    FUTURE_MID_RETURN_15M("future_mid_return_15m", StorageType.DOUBLE, true, "Look-ahead outcome: source formula uses future VWAP divided by event-minute mid minus one; null when unavailable", false),
    SELL_FIRST_GROSS_ALPHA_15M("sell_first_gross_alpha_15m", StorageType.DOUBLE, true, "Look-ahead sell-first gross alpha at 15m: entry bid (falling back to entry VWAP) divided by future ask (falling back to future VWAP) minus one", false),
    SELL_FIRST_NET_ALPHA_15M("sell_first_net_alpha_15m", StorageType.DOUBLE, true, "Sell-first gross alpha at 15m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    SELL_FIRST_OPPORTUNITY_LABEL_15M("sell_first_opportunity_label_15m", StorageType.LONG, false, "1 when sell-first gross alpha is strictly above the configured threshold at 15m; source maps missing alpha to 0", false),
    BUY_FIRST_AUX_GROSS_ALPHA_15M("buy_first_aux_gross_alpha_15m", StorageType.DOUBLE, true, "Look-ahead buy-first auxiliary gross alpha at 15m: future bid (falling back to future VWAP) divided by entry ask (falling back to entry VWAP) minus one", false),
    BUY_FIRST_AUX_NET_ALPHA_15M("buy_first_aux_net_alpha_15m", StorageType.DOUBLE, true, "Buy-first auxiliary gross alpha at 15m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    BUY_FIRST_AUX_OPPORTUNITY_LABEL_15M("buy_first_aux_opportunity_label_15m", StorageType.LONG, false, "1 when buy-first auxiliary gross alpha is strictly above the configured threshold at 15m; source maps missing alpha to 0", false),
    FUTURE_VWAP_RETURN_30M("future_vwap_return_30m", StorageType.DOUBLE, true, "Look-ahead outcome: first valid future VWAP at or after minute plus 30m divided by event-minute VWAP minus one; null when unavailable", false),
    FUTURE_MID_RETURN_30M("future_mid_return_30m", StorageType.DOUBLE, true, "Look-ahead outcome: source formula uses future VWAP divided by event-minute mid minus one; null when unavailable", false),
    SELL_FIRST_GROSS_ALPHA_30M("sell_first_gross_alpha_30m", StorageType.DOUBLE, true, "Look-ahead sell-first gross alpha at 30m: entry bid (falling back to entry VWAP) divided by future ask (falling back to future VWAP) minus one", false),
    SELL_FIRST_NET_ALPHA_30M("sell_first_net_alpha_30m", StorageType.DOUBLE, true, "Sell-first gross alpha at 30m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    SELL_FIRST_OPPORTUNITY_LABEL_30M("sell_first_opportunity_label_30m", StorageType.LONG, false, "1 when sell-first gross alpha is strictly above the configured threshold at 30m; source maps missing alpha to 0", false),
    BUY_FIRST_AUX_GROSS_ALPHA_30M("buy_first_aux_gross_alpha_30m", StorageType.DOUBLE, true, "Look-ahead buy-first auxiliary gross alpha at 30m: future bid (falling back to future VWAP) divided by entry ask (falling back to entry VWAP) minus one", false),
    BUY_FIRST_AUX_NET_ALPHA_30M("buy_first_aux_net_alpha_30m", StorageType.DOUBLE, true, "Buy-first auxiliary gross alpha at 30m minus roundtrip_cost_rate; null when gross outcome is unavailable", false),
    BUY_FIRST_AUX_OPPORTUNITY_LABEL_30M("buy_first_aux_opportunity_label_30m", StorageType.LONG, false, "1 when buy-first auxiliary gross alpha is strictly above the configured threshold at 30m; source maps missing alpha to 0", false)
    ;

    private static final Map<String, L2T0TrainingLabelField> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(L2T0TrainingLabelField::fieldName, Function.identity()));
    private final String fieldName;
    private final StorageType storageType;
    private final boolean nullable;
    private final String meaning;
    private final boolean temporal;

    L2T0TrainingLabelField(String fieldName, StorageType storageType, boolean nullable, String meaning, boolean temporal) {
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
    public boolean metric() { return switch (this) { case TRADE_DATE, SYMBOL, MARKET, BOARD, MINUTE -> false; default -> true; }; }
    public boolean key() { return this == SYMBOL || this == MINUTE; }
    public static L2T0TrainingLabelField named(String name) { return BY_NAME.get(name); }

    public Object decode(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            if (!nullable) throw new IOException("Required D089 field is null: " + fieldName);
            return null;
        }
        return switch (storageType) {
            case LONG -> {
                if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IOException("D089 LONG type mismatch: " + fieldName);
                yield node.longValue();
            }
            case DOUBLE -> {
                if (!node.isNumber()) throw new IOException("D089 DOUBLE type mismatch: " + fieldName);
                double value = node.doubleValue();
                if (!Double.isFinite(value)) throw new IOException("D089 DOUBLE is non-finite: " + fieldName);
                yield value;
            }
            case STRING, SYMBOL -> {
                if (!node.isTextual()) throw new IOException("D089 text type mismatch: " + fieldName);
                yield node.textValue();
            }
            case TIMESTAMP -> throw new IOException("D089 minute is decoded as Shanghai local wall time");
            default -> throw new IOException("Unsupported D089 storage type: " + fieldName);
        };
    }

    public Object normalize(Object value) {
        if (value == null) {
            if (!nullable) throw new IllegalArgumentException("Required D089 field is null: " + fieldName);
            return null;
        }
        return switch (storageType) {
            case LONG -> {
                if (!(value instanceof Number number)) throw new IllegalArgumentException("D089 LONG type mismatch: " + fieldName);
                try { yield new BigDecimal(number.toString()).longValueExact(); }
                catch (ArithmeticException | NumberFormatException failure) { throw new IllegalArgumentException("D089 LONG is not exact: " + fieldName, failure); }
            }
            case DOUBLE -> {
                if (!(value instanceof Number number)) throw new IllegalArgumentException("D089 DOUBLE type mismatch: " + fieldName);
                double normalized = number.doubleValue();
                if (Double.isNaN(normalized) && nullable) yield null;
                if (!Double.isFinite(normalized)) throw new IllegalArgumentException("D089 DOUBLE is non-finite: " + fieldName);
                yield normalized;
            }
            case STRING, SYMBOL -> {
                if (!(value instanceof String)) throw new IllegalArgumentException("D089 text type mismatch: " + fieldName);
                yield value;
            }
            case TIMESTAMP -> throw new IllegalArgumentException("D089 timestamp is normalized from local Parquet wall time");
            default -> throw new IllegalArgumentException("Unsupported D089 storage type: " + fieldName);
        };
    }
}
