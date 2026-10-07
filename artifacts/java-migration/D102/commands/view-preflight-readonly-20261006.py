"""D102 SELECT-only view/source preflight; never install or publish anything."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'tools'))
import prepare_d101_etf_fixture as fixture

GATE = ROOT / 'artifacts/java-migration/D101/coordinator-review-20261006.json'
EXPECTED_GATE_SHA = '2201c05e5e05c4b056a5f3170be86ed86c230fb08e1d7dcf42f93c7228fccaef'
if hashlib.sha256(GATE.read_bytes()).hexdigest() != EXPECTED_GATE_SHA:
    raise RuntimeError('D101 coordinator gate changed')
gate = json.loads(GATE.read_text(encoding='utf-8'))
if gate['decision'] != 'accepted_for_serial_progress' or not gate['next_task_may_start']:
    raise RuntimeError('D102 not admitted')
target = fixture.PrivateTarget(fixture.ROOT, 23388)
result = dict(task_id='D102', checked_at=datetime.now(timezone.utc).isoformat(),
              status='VERIFIED_SELECT_ONLY_PREFLIGHT', private_process=target.identity,
              gate_sha256=EXPECTED_GATE_SHA, writes=0)
for label, query in [('private', fixture.qwp), ('formal', fixture.audit.query)]:
    result[label] = dict(
        view=query("SELECT * FROM views() WHERE view_name='v_etf_market_overview_daily' LIMIT 2"),
        sources={table: dict(columns=query(f"SELECT * FROM table_columns('{table}')"),
                            tables=query(f"SELECT * FROM tables() WHERE table_name='{table}'"),
                            wal=query(f"SELECT * FROM wal_tables() WHERE name='{table}'"))
                 for table in ('etf_share', 'etf_daily')})
target.verify()
output = Path(__file__).with_suffix('.json')
fixture.save_new(output, result)
print(json.dumps(dict(path=str(output), status=result['status'],
                     private_view_rows=len(result['private']['view']),
                     formal_view=result['formal']['view'], writes=0), ensure_ascii=False))
