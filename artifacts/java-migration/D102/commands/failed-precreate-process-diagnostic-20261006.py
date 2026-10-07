"""Read-only diagnosis of D102 pre-CREATE PID refusal. No retry/admission."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import os
import subprocess
import sys
ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'tools'))
import audit_d102_etf_view as audit

files = sorted((ROOT / 'artifacts/java-migration/D101/commands/java-owner-bridge').glob('*.process-*.json'))
pids = set()
manifest = {}
for path in files:
    value = json.loads(path.read_text(encoding='utf-8'))
    manifest[str(path)] = audit.digest(path)
    for value_pid in [value.get('pid'), value.get('child_pid'), value.get('actual_bridge_pid'),
                      *(child.get('pid') for child in value.get('observed_children', []))]:
        if value_pid is not None:
            pids.add(value_pid)
command = "$ErrorActionPreference='Stop';$wanted=@(" + ','.join(map(str, sorted(pids))) + ");"
command += """
$all=@(Get-CimInstance Win32_Process -ErrorAction Stop)
$rows=@(foreach($procTarget in $all) {
 if($wanted -contains $procTarget.ProcessId) {
  [pscustomobject]@{pid=$procTarget.ProcessId;parent_pid=$procTarget.ParentProcessId;name=$procTarget.Name;birth=$procTarget.CreationDate.ToUniversalTime().ToString('o');executable=$procTarget.ExecutablePath}
 }
})
ConvertTo-Json -InputObject $rows -Compress
"""
native = subprocess.run([str(Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/powershell.exe'),
                         '-NoProfile', '-NonInteractive', '-Command', command], capture_output=True, text=True, timeout=30)
if native.returncode != 0:
    raise RuntimeError('Native diagnostic unavailable')
matches = json.loads(native.stdout)
gate = audit.prerequisite(audit.GATE, audit.GATE_SHA)
tables = audit.table_snapshot(True)
view = audit.view_state(True)
claim = audit.DDL_CLAIM.exists()
result = dict(task_id='D102', checked_at=datetime.now(timezone.utc).isoformat(),
              status='READONLY_PRECREATE_DIAGNOSTIC', failed_evidence_sha256=audit.digest(audit.DIRECTORY / 'view-isolated-acceptance-20261006.json'),
              historical_process_files=manifest, checked_pids=sorted(pids), native_matches=matches,
              private_tables=tables, tables_equal_accepted_typed=tables == gate['typed']['tables_after'],
              private_view=view, create_claim_exists=claim, ddl_attempts=0, owner_invocations=0,
              automatic_retry=False, next_task_admitted=False)
audit.save_new(Path(__file__).with_suffix('.json'), result)
print(json.dumps(dict(native_matches=matches, tables_equal_accepted_typed=result['tables_equal_accepted_typed'],
                     private_view=view, create_claim_exists=claim, ddl_attempts=0)))
