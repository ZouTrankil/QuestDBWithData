from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import pyarrow.dataset as ds
import psycopg2

PROJECT = Path(r"D:/work/fund_2/back-monitor")
DATASET_ROOT = PROJECT / "artifacts/level2_t0_dataset"
JAVA_ENUM = Path(
    r"C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/"
    r"com/zoutrankil/questdbwithdata/domain/L2DailyFeatureField.java"
)
OUTPUT = Path(
    r"C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/"
    r"D086/source-contract-20260924.json"
)

sys.path.insert(0, str(PROJECT / "src"))
from quant_platform.data.adapters.questdb.models.stock.l2_features import L2DailyFeatures  # noqa: E402


def credentials() -> tuple[str, str]:
    values: dict[str, str] = {}
    for line in (PROJECT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if key not in {"QUESTDB_USER", "QUESTDB_PASSWORD"}:
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    if set(values) != {"QUESTDB_USER", "QUESTDB_PASSWORD"}:
        raise RuntimeError("Source QuestDB credentials are unavailable")
    return values["QUESTDB_USER"], values["QUESTDB_PASSWORD"]


def java_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def capture() -> dict:
    model_schema = L2DailyFeatures.get_questdb_schema()
    fields = []
    for name, definition in model_schema["schema"].items():
        model_field = L2DailyFeatures.model_fields[name]
        fields.append(
            {
                "name": name,
                "storage_type": definition,
                "nullable": not model_field.is_required(),
                "meaning": model_field.description or f"Source L2 daily feature {name}",
            }
        )

    source_user, source_password = credentials()
    connection = psycopg2.connect(
        host="127.0.0.1",
        port=8812,
        dbname="qdb",
        user=source_user,
        password=source_password,
        sslmode="disable",
        connect_timeout=10,
    )
    try:
        cursor = connection.cursor()
        cursor.execute("SHOW COLUMNS FROM l2_daily_features")
        actual_columns = cursor.fetchall()
        cursor.execute("SELECT count(), min(ts), max(ts) FROM l2_daily_features")
        actual_census = cursor.fetchone()
    finally:
        connection.close()

    live_fields = [{"name": col[0], "storage_type": col[1]} for col in actual_columns]
    expected_fields = [{"name": item["name"], "storage_type": item["storage_type"]} for item in fields]
    if live_fields != expected_fields:
        raise RuntimeError("Live QuestDB l2_daily_features schema differs from the Python model")

    partition = DATASET_ROOT / "l2_daily_features/year=2026/month=09/trade_date=20260924"
    parquet_dataset = ds.dataset(str(partition), format="parquet")
    parquet_fields = [{"name": item.name, "type": str(item.type)} for item in parquet_dataset.schema]
    arrow_type = {
        "TIMESTAMP": "timestamp[ns]",
        "SYMBOL": "string",
        "STRING": "string",
        "BOOLEAN": "bool",
        "LONG": "int64",
        "DOUBLE": "double",
    }
    if [(x["name"], x["type"]) for x in parquet_fields] != [
        (item["name"], arrow_type[item["storage_type"]]) for item in fields
    ]:
        raise RuntimeError("Parquet l2_daily_features schema differs from Python/QuestDB schema")

    return {
        "dataset_id": "l2_daily_features",
        "source_root": str(DATASET_ROOT),
        "python_model": "src/quant_platform/data/adapters/questdb/models/stock/l2_features.py:L2DailyFeatures",
        "table": model_schema["table_name"],
        "timestamp_column": model_schema["timestamp_col"],
        "partition_by": model_schema["partition_by"],
        "dedup_keys": model_schema["dedup_keys"],
        "questdb_census": {
            "rows": int(actual_census[0]),
            "min_ts": str(actual_census[1]),
            "max_ts": str(actual_census[2]),
        },
        "fields": fields,
        "parquet_schema": parquet_fields,
        "schema_match": True,
        "source_row_date_for_acceptance": "2026-09-24",
        "source_row_date_file_count": len(list(partition.glob("*.parquet"))),
        "source_row_date_file_bytes": sum(p.stat().st_size for p in partition.glob("*.parquet")),
    }


def generate_enum(contract: dict) -> str:
    storage = {
        "BOOLEAN": "BOOLEAN",
        "LONG": "LONG",
        "DOUBLE": "DOUBLE",
        "STRING": "STRING",
        "SYMBOL": "SYMBOL",
        "TIMESTAMP": "TIMESTAMP",
    }
    entries = []
    for field in contract["fields"]:
        constant = field["name"].upper()
        temporal = "true" if field["storage_type"] == "TIMESTAMP" else "false"
        entries.append(
            f"    {constant}({java_string(field['name'])}, StorageType.{storage[field['storage_type']]}, "
            f"{str(field['nullable']).lower()}, {java_string(field['meaning'])}, {temporal})"
        )
    body = ",\n".join(entries)
    return f'''package com.zoutrankil.questdbwithdata.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.StorageType;

/** Frozen, typed Python model fields for D086; source nulls remain null. */
public enum L2DailyFeatureField {{
{body};

    private static final Map<String, L2DailyFeatureField> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(L2DailyFeatureField::name, Function.identity()));
    private final String name;
    private final StorageType storageType;
    private final boolean nullable;
    private final String meaning;
    private final boolean temporal;

    L2DailyFeatureField(String name, StorageType storageType, boolean nullable, String meaning, boolean temporal) {{
        this.name = name;
        this.storageType = storageType;
        this.nullable = nullable;
        this.meaning = meaning;
        this.temporal = temporal;
    }}

    public String fieldName() {{ return name; }}
    public StorageType storageType() {{ return storageType; }}
    public boolean nullable() {{ return nullable; }}
    public String meaning() {{ return meaning; }}
    public boolean temporal() {{ return temporal; }}
    public boolean identity() {{ return this == TS || this == SYMBOL; }}
    public static L2DailyFeatureField named(String name) {{ return BY_NAME.get(name); }}

    public Object decode(JsonNode node) throws IOException {{
        if (node == null || node.isNull()) {{
            if (!nullable) throw new IOException("Required D086 field is missing: " + name);
            return null;
        }}
        return switch (storageType) {{
            case BOOLEAN -> {{
                if (!node.isBoolean()) throw new IOException("D086 field is not BOOLEAN: " + name);
                yield node.booleanValue();
            }}
            case LONG -> {{
                if (!node.isIntegralNumber() || !node.canConvertToLong())
                    throw new IOException("D086 field is not LONG: " + name);
                yield node.longValue();
            }}
            case DOUBLE -> {{
                if (!node.isNumber()) throw new IOException("D086 field is not DOUBLE: " + name);
                double value = node.doubleValue();
                if (!Double.isFinite(value)) throw new IOException("D086 field is non-finite: " + name);
                yield value;
            }}
            case STRING, SYMBOL -> {{
                if (!node.isTextual()) throw new IOException("D086 field is not text: " + name);
                yield node.textValue();
            }}
            case TIMESTAMP -> throw new IOException("D086 timestamp is decoded from its business date partition");
            default -> throw new IOException("Unsupported D086 field type: " + name);
        }};
    }}

    public Object normalize(Object value) {{
        if (value == null) {{
            if (!nullable) throw new IllegalArgumentException("Required D086 field is null: " + name);
            return null;
        }}
        return switch (storageType) {{
            case BOOLEAN -> {{
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("D086 BOOLEAN type mismatch: " + name);
                yield value;
            }}
            case LONG -> {{
                if (!(value instanceof Number number)) throw new IllegalArgumentException("D086 LONG type mismatch: " + name);
                yield number.longValue();
            }}
            case DOUBLE -> {{
                if (!(value instanceof Number number)) throw new IllegalArgumentException("D086 DOUBLE type mismatch: " + name);
                double normalized = number.doubleValue();
                if (Double.isNaN(normalized) && nullable) yield null;
                if (!Double.isFinite(normalized)) throw new IllegalArgumentException("D086 DOUBLE is non-finite: " + name);
                yield normalized;
            }}
            case STRING, SYMBOL -> {{
                if (!(value instanceof String)) throw new IllegalArgumentException("D086 text type mismatch: " + name);
                yield value;
            }}
            case TIMESTAMP -> throw new IllegalArgumentException("D086 timestamp is derived from tradeDate");
            default -> throw new IllegalArgumentException("Unsupported D086 field type: " + name);
        }};
    }}
}}
'''


if __name__ == "__main__":
    result = capture()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    JAVA_ENUM.parent.mkdir(parents=True, exist_ok=True)
    JAVA_ENUM.write_text(generate_enum(result), encoding="utf-8")
    print(json.dumps({"fields": len(result["fields"]), "schema_match": result["schema_match"],
                      "live_rows": result["questdb_census"]["rows"],
                      "java_enum": str(JAVA_ENUM)}, separators=(",", ":")))
