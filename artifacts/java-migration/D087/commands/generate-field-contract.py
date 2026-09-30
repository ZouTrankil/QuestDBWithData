from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(r"C:/Users/zouqiang/IdeaProjects/QuestDBWithData")
REPORT = ROOT / "artifacts/java-migration/D087/commands/preflight-source-20260930.json"
DEST = ROOT / "src/main/java/com/zoutrankil/questdbwithdata/domain/L2IntradayBarFeatureField.java"
QDB = {"trade_date": "STRING", "symbol": "SYMBOL", "market": "STRING", "board": "STRING",
       "minute": "TIMESTAMP", "tick_count": "LONG", "has_trade_1m": "LONG"}
REQUIRED = {"trade_date", "symbol", "market", "board", "minute", "volume", "amount",
            "tick_count", "has_trade_1m"}
MEANINGS = {
    "trade_date": "A-share business date in YYYYMMDD; must equal minute interpreted in Asia/Shanghai",
    "symbol": "Canonical six-digit stock code with exchange suffix; one part is selected per symbol and date",
    "market": "Market suffix derived by the Python stock symbol mapping",
    "board": "Board category derived by the Python stock symbol mapping",
    "minute": "Minute floor of source event time, naive Asia/Shanghai wall time in Parquet and UTC instant in QuestDB",
    "open": "First scaled trade price in the minute",
    "high": "Maximum scaled trade price in the minute",
    "low": "Minimum scaled trade price in the minute",
    "close": "Last scaled trade price in the minute",
    "volume": "Sum of source trade volume in the minute; empty trade minutes are zero",
    "amount": "Sum of scaled trade price times volume; empty trade minutes are zero",
    "tick_count": "Number of valid positive-price trade rows in the minute; empty trade minutes are zero",
    "active_buy_amount": "Trade amount whose source Side equals zero",
    "active_sell_amount": "Trade amount whose source Side equals one",
    "vwap": "Minute trade amount divided by minute volume; null when volume is zero",
    "has_trade_1m": "One when the minute has a valid trade, otherwise zero",
    "bid1": "Last valid best bid price in the minute",
    "ask1": "Last valid best ask price in the minute",
    "mid": "Midpoint of a valid non-crossed best bid and ask",
    "spread": "Relative best-quote spread, ask minus bid divided by midpoint",
    "microprice": "Best-quote size-weighted midpoint",
    "quote_count": "Number of source quote snapshots grouped into the minute",
    "ofi_1m": "One-minute change in top-level bid depth minus change in ask depth",
    "active_buy_ratio": "Active buy amount divided by minute amount",
    "active_sell_ratio": "Active sell amount divided by minute amount",
    "vwap_gap_to_mid": "Relative minute VWAP gap from quote midpoint",
    "vwap_gap_to_open": "Relative minute VWAP gap from minute open",
    "vwap_slope_3m": "Three-row VWAP percentage change",
    "vwap_slope_5m": "Five-row VWAP percentage change",
    "cancel_ratio": "Share of minute order rows with source OrderType -1 or -11",
}
for levels in (1, 5, 10):
    MEANINGS[f"bid_depth_{levels}"] = f"Sum of bid sizes from levels 1 through {levels} in the last quote of the minute"
    MEANINGS[f"ask_depth_{levels}"] = f"Sum of ask sizes from levels 1 through {levels} in the last quote of the minute"
    MEANINGS[f"depth_{levels}"] = f"Bid depth plus ask depth for the first {levels} quote levels"
    MEANINGS[f"obi_{levels}"] = f"(bid depth minus ask depth) divided by total depth for {levels} quote levels"
for horizon in (1, 3, 5, 10, 15, 30):
    MEANINGS[f"ret_{horizon}m"] = f"VWAP percentage change over {horizon} rows"
    MEANINGS[f"vol_ratio_{horizon}m"] = f"Minute volume divided by trailing {horizon}-row mean volume"
    MEANINGS[f"range_{horizon}m"] = f"Rolling high divided by rolling low minus one over {horizon} rows"

report = json.loads(REPORT.read_text(encoding="utf-8"))
columns = report["live_questdb_columns"]
if len(columns) != 60 or [item["name"] for item in columns] != [item["name"] for item in report["selected_source_columns"]]:
    raise RuntimeError("D087 live schema and selected Parquet schema field order differ")
fields = []
entries = []
for column in columns:
    name = column["name"]
    storage = QDB.get(name, column["type"])
    if column["type"] != storage:
        raise RuntimeError(f"Unexpected D087 QuestDB type for {name}")
    if name not in MEANINGS:
        raise RuntimeError(f"Missing field meaning for {name}")
    enum = re.sub(r"[^A-Z0-9]+", "_", name.upper()).strip("_")
    nullable = name not in REQUIRED
    temporal = name in {"trade_date", "minute"}
    fields.append((enum, name, storage, nullable, MEANINGS[name], temporal))
    entries.append(
        f'    {enum}("{name}", StorageType.{storage}, {str(nullable).lower()}, '
        f'{json.dumps(MEANINGS[name], ensure_ascii=False)}, {str(temporal).lower()})'
    )

body = ",\n".join(entries)
source = f'''package com.zoutrankil.questdbwithdata.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.StorageType;

/** Frozen D087 physical field mapping; local Parquet values normalize to the audited QuestDB types. */
public enum L2IntradayBarFeatureField {{
{body};

    private static final Map<String, L2IntradayBarFeatureField> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(L2IntradayBarFeatureField::fieldName, Function.identity()));
    private final String fieldName;
    private final StorageType storageType;
    private final boolean nullable;
    private final String meaning;
    private final boolean temporal;

    L2IntradayBarFeatureField(String fieldName, StorageType storageType, boolean nullable, String meaning, boolean temporal) {{
        this.fieldName = fieldName;
        this.storageType = storageType;
        this.nullable = nullable;
        this.meaning = meaning;
        this.temporal = temporal;
    }}

    public String fieldName() {{ return fieldName; }}
    public StorageType storageType() {{ return storageType; }}
    public boolean nullable() {{ return nullable; }}
    public String meaning() {{ return meaning; }}
    public boolean temporal() {{ return temporal; }}
    public boolean metric() {{ return switch (this) {{
        case TRADE_DATE, SYMBOL, MARKET, BOARD, MINUTE -> false;
        default -> true;
    }}; }}
    public boolean key() {{ return this == SYMBOL || this == MINUTE; }}
    public static L2IntradayBarFeatureField named(String name) {{ return BY_NAME.get(name); }}

    public Object decode(JsonNode node) throws IOException {{
        if (node == null || node.isNull()) {{
            if (!nullable) throw new IOException("Required D087 field is null: " + fieldName);
            return null;
        }}
        return switch (storageType) {{
            case LONG -> {{
                if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IOException("D087 LONG type mismatch: " + fieldName);
                yield node.longValue();
            }}
            case DOUBLE -> {{
                if (!node.isNumber()) throw new IOException("D087 DOUBLE type mismatch: " + fieldName);
                double value = node.doubleValue();
                if (!Double.isFinite(value)) throw new IOException("D087 DOUBLE is non-finite: " + fieldName);
                yield value;
            }}
            case STRING, SYMBOL -> {{
                if (!node.isTextual()) throw new IOException("D087 text type mismatch: " + fieldName);
                yield node.textValue();
            }}
            case TIMESTAMP -> throw new IOException("D087 minute is decoded as Shanghai local wall time");
            default -> throw new IOException("Unsupported D087 storage type: " + fieldName);
        }};
    }}

    public Object normalize(Object value) {{
        if (value == null) {{
            if (!nullable) throw new IllegalArgumentException("Required D087 field is null: " + fieldName);
            return null;
        }}
        return switch (storageType) {{
            case LONG -> {{
                if (!(value instanceof Number number)) throw new IllegalArgumentException("D087 LONG type mismatch: " + fieldName);
                try {{ yield new BigDecimal(number.toString()).longValueExact(); }}
                catch (ArithmeticException | NumberFormatException failure) {{ throw new IllegalArgumentException("D087 LONG is not exact: " + fieldName, failure); }}
            }}
            case DOUBLE -> {{
                if (!(value instanceof Number number)) throw new IllegalArgumentException("D087 DOUBLE type mismatch: " + fieldName);
                double normalized = number.doubleValue();
                if (Double.isNaN(normalized) && nullable) yield null;
                if (!Double.isFinite(normalized)) throw new IllegalArgumentException("D087 DOUBLE is non-finite: " + fieldName);
                yield normalized;
            }}
            case STRING, SYMBOL -> {{
                if (!(value instanceof String)) throw new IllegalArgumentException("D087 text type mismatch: " + fieldName);
                yield value;
            }}
            case TIMESTAMP -> throw new IllegalArgumentException("D087 timestamp is normalized from local Parquet wall time");
            default -> throw new IllegalArgumentException("Unsupported D087 storage type: " + fieldName);
        }};
    }}
}}
'''
DEST.write_text(source, encoding="utf-8")
print(json.dumps({"fields": len(fields), "metrics": sum(item[0] not in {"TRADE_DATE", "SYMBOL", "MARKET", "BOARD", "MINUTE"} for item in fields), "destination": str(DEST)}))
