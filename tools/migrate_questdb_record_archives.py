"""Publish lossless JDBC record snapshots through the existing Python SQLite stores.

This tool never sends SQL to QuestDB and never overwrites an existing business ledger.
Run QuestDbRecordSnapshot.java first. Raw snapshots remain the recovery authority.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import sqlite3
import sys


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def normalized(value, kind: str, date_only: bool = False):
    if value is None:
        return None
    kind = kind.upper()
    if kind in {"DOUBLE", "FLOAT"}:
        v = float(value)
        if not math.isfinite(v):
            raise ValueError("Non-finite value needs explicit handling")
        return (v if v else 0.0).hex()
    if kind in {"LONG", "INT", "SHORT", "BYTE", "BOOLEAN"}:
        v = int(value)
        if v != value:
            raise ValueError("Integer precision loss")
        return v
    if kind in {"TIMESTAMP", "TIMESTAMP_NS", "DATE"}:
        v = value if isinstance(value, datetime) else datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if v.tzinfo is not None:
            from datetime import timezone
            v = v.astimezone(timezone.utc).replace(tzinfo=None)
        if date_only:
            if v.time() != datetime.min.time():
                raise ValueError("Date-only projection would discard intraday time")
            return v.date().isoformat()
        return v.isoformat(timespec="microseconds")
    if not isinstance(value, str):
        raise ValueError(f"Unexpected string representation: {type(value).__name__}")
    return value


def fingerprint(rows, schema, date_columns=()):
    columns = sorted(schema)
    encoded = []
    for row in rows:
        if set(row) != set(schema):
            raise ValueError("Full-field verification requires exactly the declared columns")
        encoded.append(json.dumps(
            [normalized(row[c], schema[c], c in date_columns) for c in columns],
            ensure_ascii=False, separators=(",", ":"), allow_nan=False,
        ))
    counts = Counter(encoded)
    h = hashlib.sha256()
    for row, count in sorted(counts.items()):
        h.update(json.dumps([row, count], ensure_ascii=False, separators=(",", ":")).encode())
        h.update(b"\n")
    return {"rows": len(encoded), "distinct_rows": len(counts), "sha256": h.hexdigest()}


def load_snapshot(root: Path, table: str):
    data = json.loads((root / "source" / f"{table}.json").read_text())
    if data["before"] != data["after"]:
        raise ValueError(f"Unstable source: {table}")
    cols = data["columns"]
    rows = []
    for values in data["dataset"]:
        row = {}
        for c, value in zip(cols, values, strict=True):
            if value is not None and c["type"].lower() in {"timestamp", "timestamptz", "date"}:
                value = datetime.fromisoformat(value.replace("Z", "+00:00")).replace(tzinfo=None)
            row[c["name"]] = value
        rows.append(row)
    if len(rows) != data["row_count"]:
        raise ValueError("Truncated snapshot")
    schema = {c["column"]: c["type"] for c in data["schema"]}
    if set(schema) != {c["name"] for c in cols}:
        raise ValueError("Snapshot metadata differs from result fields")
    return rows, schema


def readonly_rows(path: Path, table: str):
    with sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True) as db:
        db.row_factory = sqlite3.Row
        return [dict(row) for row in db.execute(f'SELECT * FROM "{table}"')]


def check_integrity(path: Path):
    with sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True) as db:
        if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            raise ValueError(f"SQLite integrity check failed: {path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python-root", type=Path, required=True)
    parser.add_argument("--audit-root", type=Path, required=True)
    parser.add_argument("--deploy", action="store_true", help="Atomically install verified new ledgers at consumer paths")
    args = parser.parse_args()
    root = args.audit_root.resolve()
    pyroot = args.python_root.resolve()
    sys.path[:0] = [str(pyroot), str(pyroot / "src")]
    import pandas as pd
    from quant_platform.best_trader.adapters.local_trade_evidence import LocalTradeEvidenceStore, MODELS
    from quant_platform.best_trader.adapters.local_broker_records import LocalBrokerRecordStore, TABLES as BROKER
    from quant_platform.research.adapters.strategy_identity_files import StrategyIdentityFileStore
    from quant_platform.research.adapters.strategy_legacy_files import StrategyLegacyFileStore
    from quant_platform.research.adapters.strategy_production_files import StrategyProductionFileStore, _DATE_COLUMNS
    from quant_platform.research.adapters.strategy_production_store import PRODUCTION_TABLES
    from quant_platform.research.adapters.etf7030.etf7030_persistence import TABLES as LEGACY

    trade = {t: m.get_questdb_schema() for t, m in MODELS.items()}
    groups = [
        ("trade", LocalTradeEvidenceStore, trade, False),
        ("broker", LocalBrokerRecordStore, BROKER, True),
        ("legacy", StrategyLegacyFileStore, LEGACY, True),
        ("production", StrategyProductionFileStore, PRODUCTION_TABLES, True),
        ("identity", StrategyIdentityFileStore, {"strategy_templates": None, "strategy_instances": None}, False),
    ]
    staging = root / "sqlite"
    staging.mkdir(exist_ok=True, mode=0o700)
    report = {"format": "questdb-record-migration.v1", "python_root": str(pyroot), "groups": {}, "tables": {}}
    prepared = []
    for group, cls, specs, date_only in groups:
        path = staging / f"{group}.sqlite3"
        target = cls().path
        if target.exists():
            raise FileExistsError(f"Existing consumer ledger must be reconciled, never overwritten: {target}")
        if path.exists():
            raise FileExistsError(f"Existing staging ledger must not be overwritten: {path}")
        store = cls(path)
        store.ensure_schema()
        path.chmod(0o600)
        frames, expected = {}, {}
        for table, spec in specs.items():
            rows, physical = load_snapshot(root, table)
            if spec is not None and set(spec["schema"]) != set(physical):
                raise ValueError(f"Application/source schema columns differ: {table}")
            schema = spec["schema"] if spec is not None else physical
            expected[table] = (fingerprint(rows, schema, _DATE_COLUMNS if date_only else ()), schema)
            frames[table] = pd.DataFrame(rows, columns=list(schema), dtype=object)
        if group == "identity":
            store.append_batch({"templates": frames["strategy_templates"], "instances": frames["strategy_instances"]})
        elif group == "trade":
            store.append_batch(frames)
        else:
            store.publish_batch(frames)
        store.verify_schema()
        check_integrity(path)
        for table, (source_fp, schema) in expected.items():
            if group == "identity":
                kind = "templates" if table == "strategy_templates" else "instances"
                saved = [r for r in readonly_rows(path, "records") if r["kind"] == kind]
                rows = [json.loads(r["payload"]) for r in saved]
                for row in saved:
                    if hashlib.sha256(f'{kind}:{row["payload"]}'.encode()).hexdigest() != row["record_sha256"]:
                        raise ValueError("Identity revision hash mismatch")
                if saved:
                    store.latest(kind, saved[0]["identity"])
            else:
                # Exercise the same formal read interface used by application consumers.
                frame = store.read_sql(f'SELECT * FROM "{table}"')
                rows = frame.to_dict("records")
                rows = [{k: None if pd.isna(v) else v for k, v in row.items()} for row in rows]
            target_fp = fingerprint(rows, schema, _DATE_COLUMNS if date_only else ())
            if source_fp != target_fp:
                raise ValueError(f"Full-field multiset mismatch: {table}: {source_fp} != {target_fp}")
            report["tables"][table] = {"source": source_fp, "target": target_fp, "status": "VERIFIED", "snapshot_sha256": digest(root / "source" / f"{table}.json")}
            print(f'{table}: verified {source_fp["rows"]} complete rows', flush=True)
        prepared.append((group, cls, path, target))
        report["groups"][group] = {"staged": str(path), "target": str(target), "sha256": digest(path), "status": "VERIFIED"}
    # A durable source archive plus an independent SQLite backup precede publication.
    backups = root / "sqlite-backup"
    backups.mkdir(exist_ok=True, mode=0o700)
    for group, cls, path, target in prepared:
        backup = backups / path.name
        with sqlite3.connect(path) as source, sqlite3.connect(backup) as dest:
            source.backup(dest)
        check_integrity(backup)
        backup.chmod(0o600)
        report["groups"][group]["backup"] = str(backup)
        report["groups"][group]["backup_sha256"] = digest(backup)
    (root / "migration-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    if args.deploy:
        for group, cls, path, target in prepared:
            target.parent.mkdir(parents=True, exist_ok=True)
            # Hard-link publication is atomic, fails if a concurrent writer created the target,
            # and cannot silently replace a business ledger. Both paths are on the same volume.
            temporary = target.with_name(f".{target.name}.{root.name}.partial")
            with path.open("rb") as src, temporary.open("xb") as dest:
                temporary.chmod(0o600)
                shutil.copyfileobj(src, dest)
                dest.flush()
                os.fsync(dest.fileno())
            os.link(temporary, target)
            temporary.unlink()
            cls(target).verify_schema()
            check_integrity(target)
            report["groups"][group]["status"] = "DEPLOYED"
            (root / "migration-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        report["status"] = "DEPLOYED_AND_VERIFIED"
    else:
        report["status"] = "VERIFIED_NOT_DEPLOYED"
    (root / "migration-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(report["status"], flush=True)


if __name__ == "__main__":
    main()
