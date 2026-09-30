"""Verify one bounded D091 cache slice with the Python owner's exact digest implementation."""
from __future__ import annotations

import argparse
import hashlib
import gzip
import json
import re
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--python-project", default="D:/work/fund_2/back-monitor")
    args = parser.parse_args()

    project = Path(args.python_project).resolve()
    sys.path.insert(0, str(project / "src"))
    import pandas as pd
    from quant_platform.data.adapters.questdb.backtest_cache import FIELDS, content_digest

    source_bytes = Path(args.input).read_bytes()
    payload_bytes = gzip.decompress(source_bytes) if args.input.lower().endswith(".gz") else source_bytes
    evidence = json.loads(payload_bytes)
    coverage = evidence["coverage"]
    rows = evidence["cache_rows"]
    if not re.fullmatch(r"[0-9a-f]{64}", coverage["source_version"]):
        raise ValueError("source_version is not a lowercase SHA-256 fingerprint")
    if not re.fullmatch(r"[0-9a-f]{64}", coverage["content_digest"]):
        raise ValueError("content_digest is not a lowercase SHA-256 digest")
    keys = [(row["trade_date"], row["ts_code"]) for row in rows]
    if len(keys) != len(set(keys)):
        raise ValueError("cache slice contains duplicate (trade_date, ts_code) keys")
    if len(rows) != int(coverage["row_count"]):
        raise ValueError("cache row count does not match the published receipt")

    actual_digest = content_digest(pd.DataFrame(rows))
    passed = actual_digest == coverage["content_digest"]
    result = {
        "task_id": "D091",
        "status": "VERIFIED" if passed else "FAILED",
        "algorithm": "Python backtest_cache.content_digest (canonical sorted pandas hash_pandas_object bytes)",
        "trade_date": coverage["trade_date"],
        "source_version": coverage["source_version"],
        "expected_row_count": int(coverage["row_count"]),
        "actual_row_count": len(rows),
        "unique_business_keys": len(keys),
        "expected_content_digest": coverage["content_digest"],
        "actual_content_digest": actual_digest,
        "digest_matches": passed,
        "input_sha256": hashlib.sha256(source_bytes).hexdigest(),
        "uncompressed_payload_sha256": hashlib.sha256(payload_bytes).hexdigest(),
        "input_path": str(Path(args.input).resolve()),
        "checked_fields": list(FIELDS),
        "sample_keys": keys[:3],
        "database_access": "none; input captured by bounded Java SELECTs",
        "writes": "none",
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in (
        "task_id", "status", "trade_date", "expected_row_count", "actual_row_count", "digest_matches"
    )}, ensure_ascii=False))
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
