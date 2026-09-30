from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

WORKSPACE = Path(r"C:/Users/zouqiang/IdeaProjects/QuestDBWithData")
READER = WORKSPACE / "tools/read_l2_daily_features.py"
PYTHON = Path(r"D:/work/fund_2/back-monitor/.venv/Scripts/python.exe")
ROOT = Path(r"D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset")
OUT = WORKSPACE / "artifacts/java-migration/D086/commands"
COMMON = [
    str(PYTHON), str(READER), "--dataset-root", str(ROOT),
    "--from-date", "20260921", "--to-date", "20260924",
    "--page-rows", "200", "--max-files", "25000", "--max-rows", "300000",
    "--max-bytes", str(256 * 1024 * 1024), "--max-output-bytes", str(256 * 1024 * 1024),
    "--symbol", "000001.SZ",
]


def invoke(args: list[str]) -> bytes:
    result = subprocess.run(args, cwd=WORKSPACE, capture_output=True, timeout=300, check=False)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").splitlines()
        message = detail[0].split(":", 1)[0] if detail else "SourceProcessFailed"
        raise RuntimeError(f"D086 sample source check failed: {message}")
    return result.stdout


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    inspect = invoke(COMMON + ["--inspect"])
    parsed = json.loads(inspect)
    if parsed.get("kind") != "inspection" or parsed.get("selectedRows") != 4:
        raise RuntimeError("D086 sample inspection did not select four rows")
    (OUT / "source-inspection-20260921-24.json").write_bytes(inspect + b"\n")
    stream = invoke(COMMON + ["--stream", "--expected-fingerprint", parsed["sourceFingerprint"]])
    lines = [line for line in stream.splitlines() if line]
    records = [json.loads(line) for line in lines]
    pages = [record for record in records if record.get("kind") == "page"]
    completion = records[-1] if records else {}
    if len(pages) != 4 or sum(len(page.get("rows", [])) for page in pages) != 4:
        raise RuntimeError("D086 sample JSONL stream did not emit four rows across four pages")
    if completion.get("kind") != "completion" or completion.get("complete") is not True:
        raise RuntimeError("D086 sample JSONL stream has no completion receipt")
    (OUT / "source-parquet-20260921-24.jsonl").write_bytes(stream)
    print(json.dumps({
        "source_rows": parsed["selectedRows"],
        "manifest_rows_scanned": parsed["sourceRows"],
        "files": parsed["files"],
        "source_bytes": parsed["sourceBytes"],
        "pages": len(pages),
        "source_fingerprint": parsed["sourceFingerprint"],
        "schema_fingerprint": parsed["schemaFingerprint"],
        "complete": completion["completeForSelectedSymbols"],
    }, separators=(",", ":")))


if __name__ == "__main__":
    main()
