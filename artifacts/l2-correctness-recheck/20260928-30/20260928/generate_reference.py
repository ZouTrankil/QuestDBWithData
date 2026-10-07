"""Run the canonical Python daily-feature pipeline on isolated real CSV samples."""
from __future__ import annotations

import argparse
import gc
import hashlib
import json
import math
import sys
import time
from datetime import datetime
from pathlib import Path

import pandas as pd

REPO = Path(r"D:\work\fund_2\back-monitor")
sys.path.insert(0, str(REPO / "src"))

from quant_platform.data.adapters.connectors.level2.sources.dfcf_csv_source import DfcfCsvLevel2Source
from quant_platform.data.adapters.questdb.models.stock.l2_features import L2DailyFeatures
from quant_platform.data.composition.level2_pipeline import KuakeSyncPipeline
import quant_platform.data.composition.level2_pipeline as pipeline_module


def clean(value):
    if isinstance(value, dict):
        return {str(k): clean(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [clean(v) for v in value]
    if hasattr(value, "item"):
        return clean(value.item())
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, (datetime, pd.Timestamp)):
        return value.isoformat()
    return value


def save_json(path, value):
    path.write_text(json.dumps(clean(value), ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--symbols", nargs="+", default=["000001.SZ", "600000.SH", "300750.SZ", "688981.SH", "510300.SH", "159915.SZ", "588000.SH"])
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    source = DfcfCsvLevel2Source(args.input)
    pipeline = KuakeSyncPipeline(source=source)
    date = datetime(2026, 9, 28)
    stage_results = {}
    stage_times = {}

    for name in ["WideTableBuilder", "EntropyFeatureBuilder", "WashTradeBuilder", "NetFlowBuilder", "MainFundsBuilder", "DataQualityFeatureBuilder", "OrderLifecycleBuilder", "IntradaySegmentFeatureBuilder", "TradeSignValidationBuilder", "LOBTransitionFeatureBuilder", "GMMBehaviorBuilder", "MainForceIntensityBuilder", "SpoofingFeatureBuilder", "MicrostructureFeatureBuilder"]:
        cls = getattr(pipeline_module, name)
        method_name = "build_p3_features" if name == "SpoofingFeatureBuilder" else "build_p6_features" if name == "MicrostructureFeatureBuilder" else "get_features"
        original = getattr(cls, method_name)

        def wrapped(self, *values, _original=original, _name=name, **kwargs):
            started = time.perf_counter()
            result = _original(self, *values, **kwargs)
            stage_results[_name] = result
            stage_times[_name] = time.perf_counter() - started
            return result

        setattr(cls, method_name, wrapped)

    raw_counts = {}
    original_load = pipeline.parser.load_stock_data

    def capture_raw(*values, **kwargs):
        data = original_load(*values, **kwargs)
        raw_counts.clear()
        raw_counts.update({name: {"records": len(frame), "columns": list(frame.columns), "null_counts": {col: int(count) for col, count in frame.isna().sum().items() if count}} for name, frame in data.items()})
        return data

    pipeline.parser.load_stock_data = capture_raw
    schema = L2DailyFeatures.get_questdb_schema()
    fields = {name: {"annotation": str(field.annotation), "required": field.is_required(), "default": clean(field.default) if not field.is_required() else "REQUIRED", "description": field.description} for name, field in L2DailyFeatures.model_fields.items()}
    save_json(output / "schema.json", {"model_fields": fields, "field_count": len(fields), "questdb": schema, "parser_version": pipeline.parser_version, "feature_version": pipeline_module.L2_FEATURE_VERSION})
    reference_files = [REPO / "src/quant_platform/data/composition/level2_pipeline.py", REPO / "src/quant_platform/data/adapters/connectors/level2/order_normalizer.py", REPO / "src/quant_platform/data/adapters/connectors/level2/kuake_parser.py", REPO / "src/quant_platform/data/adapters/connectors/level2/sources/dfcf_csv_source.py", *sorted((REPO / "src/quant_platform/data/application/level2_features").glob("*.py"))]
    save_json(output / "source-hashes.json", {str(path.relative_to(REPO)): hashlib.sha256(path.read_bytes()).hexdigest() for path in reference_files})
    summary = {"date": "20260928", "input": args.input, "pipeline_api": "KuakeSyncPipeline(source=DfcfCsvLevel2Source(input)).process_symbol_date(symbol, datetime(2026,9,28), load_snapshot=True)", "field_count": len(fields), "symbols": {}}

    for symbol in args.symbols:
        stage_results.clear()
        stage_times.clear()
        print(f"START {symbol}", flush=True)
        started = time.perf_counter()
        try:
            model = pipeline.process_symbol_date(symbol, date, load_snapshot=True)
            if model is None:
                raise RuntimeError("Canonical pipeline returned None")
            values = model.model_dump()
            nonfinite = [name for name, value in values.items() if isinstance(value, float) and not math.isfinite(value)]
            save_json(output / f"{symbol}.json", values)
            save_json(output / f"{symbol}.stages.json", {"builders": stage_results, "seconds": stage_times, "raw_tables": raw_counts})
            entry = {"success": True, "elapsed_seconds": time.perf_counter() - started, "nonfinite_fields_converted_to_null": nonfinite, "null_fields": [name for name, value in clean(values).items() if value is None], "populated_fields": sum(value is not None for value in clean(values).values()), "raw_records": {name: value["records"] for name, value in raw_counts.items()}, "feature_records": {name: values.get(name) for name in ("deal_records", "order_records", "snapshot_records", "total_records", "clean_records", "large_order_records")}, "feature_version": values.get("feature_version"), "parser_version": values.get("parser_version")}
            print(f"DONE {symbol} {entry['elapsed_seconds']:.2f}s fields={entry['populated_fields']}/{len(fields)}", flush=True)
        except Exception as exc:
            import traceback
            entry = {"success": False, "elapsed_seconds": time.perf_counter() - started, "error": str(exc), "traceback": traceback.format_exc()}
            print(f"FAILED {symbol}: {exc}", flush=True)
        summary["symbols"][symbol] = entry
        save_json(output / "summary.json", summary)
        source._sz_deal_csv_cache.clear()
        gc.collect()
    return 0 if all(entry["success"] for entry in summary["symbols"].values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())

