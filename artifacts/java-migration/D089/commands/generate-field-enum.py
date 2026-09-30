from pathlib import Path
import json
import re

root = Path.cwd()
card = root / "docs/migration-tasks-20260929/10-l2/D089-l2_t0_training_labels.md"
target = root / "src/main/java/com/zoutrankil/questdbwithdata/domain/L2T0TrainingLabelField.java"
rows = re.findall(r"\| `([^`]+)` \| `(STRING|SYMBOL|TIMESTAMP|DOUBLE|LONG)` \|", card.read_text(encoding="utf-8"))
if len(rows) != 62 or len({name for name, _ in rows}) != 62:
    raise SystemExit(f"D089 frozen card schema has {len(rows)} fields, expected 62 unique fields")

costs = {"roundtrip_cost_rate", "stamp_tax_rate", "commission_rate", "slippage_bps"}
identity = {"trade_date", "symbol", "market", "board", "minute"}

def constant(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", name).upper()

def meaning(name: str) -> str:
    if name == "trade_date": return "A-share business date YYYYMMDD; equals minute interpreted in Asia/Shanghai"
    if name == "symbol": return "Canonical six-digit stock code with exchange suffix and part of the complete key"
    if name == "market": return "Market suffix derived by the Python stock symbol mapping"
    if name == "board": return "Board category derived by the Python stock symbol mapping"
    if name == "minute": return "Minute wall time in Asia/Shanghai in Parquet; stored as a UTC instant"
    if name == "executability_label": return "1 when spread, top-one depth, and entry bid/ask meet the Python execution rule; otherwise 0"
    if name == "executability_reason": return "Python execution rule reason: quote_depth_ok or wide_spread_or_thin_depth"
    if name == "policy_label": return "Python placeholder rule_data_missing; not an executable policy decision"
    if name == "policy_reason": return "Python missing-input reason for inventory, limits, and price-cage data"
    if name == "primary_t0_side": return "Python placeholder sell_first_inventory_required; inventory is not present here"
    if name == "roundtrip_cost_rate": return "Two commissions plus stamp tax plus slippage_bps divided by 10000"
    if name == "stamp_tax_rate": return "Python-configured stock stamp-tax rate used in round-trip cost"
    if name == "commission_rate": return "Python-configured one-way commission rate"
    if name == "slippage_bps": return "Python-configured round-trip slippage in basis points"
    horizon = re.search(r"_(1|3|5|10|15|30)m$", name)
    hm = horizon.group(1) if horizon else ""
    if name.startswith("future_vwap_return_"):
        return f"Look-ahead outcome: first valid future VWAP at or after minute plus {hm}m divided by event-minute VWAP minus one; null when unavailable"
    if name.startswith("future_mid_return_"):
        return f"Look-ahead outcome: source formula uses future VWAP divided by event-minute mid minus one; null when unavailable"
    if name.startswith("sell_first_gross_alpha_"):
        return f"Look-ahead sell-first gross alpha at {hm}m: entry bid (falling back to entry VWAP) divided by future ask (falling back to future VWAP) minus one"
    if name.startswith("sell_first_net_alpha_"):
        return f"Sell-first gross alpha at {hm}m minus roundtrip_cost_rate; null when gross outcome is unavailable"
    if name.startswith("sell_first_opportunity_label_"):
        return f"1 when sell-first gross alpha is strictly above the configured threshold at {hm}m; source maps missing alpha to 0"
    if name.startswith("buy_first_aux_gross_alpha_"):
        return f"Look-ahead buy-first auxiliary gross alpha at {hm}m: future bid (falling back to future VWAP) divided by entry ask (falling back to entry VWAP) minus one"
    if name.startswith("buy_first_aux_net_alpha_"):
        return f"Buy-first auxiliary gross alpha at {hm}m minus roundtrip_cost_rate; null when gross outcome is unavailable"
    if name.startswith("buy_first_aux_opportunity_label_"):
        return f"1 when buy-first auxiliary gross alpha is strictly above the configured threshold at {hm}m; source maps missing alpha to 0"
    raise SystemExit(f"Missing D089 semantic description for {name}")

fields = []
for name, storage in rows:
    java_type = {"STRING": "STRING", "SYMBOL": "SYMBOL", "TIMESTAMP": "TIMESTAMP", "DOUBLE": "DOUBLE", "LONG": "LONG"}[storage]
    nullable = storage == "DOUBLE" and name not in costs
    temporal = name in {"trade_date", "minute"}
    fields.append(f"    {constant(name)}({json.dumps(name)}, StorageType.{java_type}, {str(nullable).lower()}, {json.dumps(meaning(name), ensure_ascii=False)}, {str(temporal).lower()})")
source = '''package com.zoutrankil.questdbwithdata.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.StorageType;

/** Explicit 62-column D089 source-to-QuestDB field contract. */
public enum L2T0TrainingLabelField {
'''
source += ",\n".join(fields) + '''
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
'''
target.write_text(source, encoding="utf-8", newline="\n")
print(f"generated {len(rows)} explicit fields at {target.relative_to(root)}")
