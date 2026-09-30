#!/usr/bin/env python3
"""Read-only D087 submitted/verified/reused totals from its SQLite sync ledger."""
from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: summarize-ledger.py LEDGER OUTPUT_JSON")
    ledger = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    if not ledger.is_file() or ledger.is_symlink():
        raise SystemExit("ledger must be an existing regular file")
    uri = ledger.as_uri() + "?mode=ro"
    with sqlite3.connect(uri, uri=True) as connection:
        connection.row_factory = sqlite3.Row
        runs = connection.execute(
            "SELECT id,job_id,job_version,logical_date,target_id FROM sync_runs "
            "WHERE job_id IN ('data.l2_intraday_bar_features','write.l2_intraday_bar_features') "
            "ORDER BY rowid"
        ).fetchall()
        summaries = []
        for run in runs:
            slices = connection.execute(
                "SELECT id,state,payload_json FROM sync_entries WHERE run_id=? AND kind='SLICE'",
                (run['id'],),
            ).fetchall()
            source_rows = verified_rows = reused_rows = 0
            submitted_rows = 0
            acknowledged_batches = 0
            for entry in slices:
                payload = json.loads(entry['payload_json'])
                proof = payload.get('verification', {})
                count = int(proof.get('expectedRows', payload.get('returnedRows', 0)))
                if entry['state'] in ('VERIFIED', 'VERIFIED_EMPTY'):
                    source_rows += count
                    verified_rows += count
                    if 'reusedCheckpoint' in payload:
                        reused_rows += count
                events = connection.execute(
                    "SELECT state,payload_json FROM sync_events WHERE entry_id=? ORDER BY revision",
                    (entry['id'],),
                ).fetchall()
                for event in events:
                    if event['state'] != 'ACKNOWLEDGED':
                        continue
                    write = json.loads(event['payload_json']).get('writeResult', {})
                    submitted_rows += int(write.get('submittedRows', 0))
                    acknowledged_batches += len(write.get('receipts', []))
            summaries.append({
                'runId': run['id'], 'jobId': run['job_id'], 'jobVersion': run['job_version'],
                'logicalDate': run['logical_date'], 'targetId': run['target_id'],
                'state': connection.execute(
                    "SELECT state FROM sync_entries WHERE id=?", (run['id'],)
                ).fetchone()['state'],
                'sourceRows': source_rows, 'submittedRows': submitted_rows,
                'verifiedRows': verified_rows, 'reusedRows': reused_rows,
                'acknowledgedBatches': acknowledged_batches,
                'sliceCount': len(slices),
            })
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'ledger': str(ledger), 'runs': summaries}, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'runCount': len(summaries), 'output': str(output)}, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
