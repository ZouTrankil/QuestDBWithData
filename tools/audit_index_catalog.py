"""Read-only D003 catalog baseline. No application imports or data writes."""
from pathlib import Path
from datetime import datetime, timezone
from urllib.request import Request, urlopen
from urllib.parse import urlencode
import base64
import json
import yaml

root = Path(__file__).resolve().parents[1]
config = yaml.safe_load((root / "src/main/resources/application.yml").read_text(encoding="utf-8"))["app"]["questdb"]
auth = base64.b64encode(f"{config['username']}:{config['password']}".encode()).decode()
endpoint = f"http://{config['host']}:{config['qwp-port']}/exec"
queries = {
    "table": "SELECT * FROM tables() WHERE table_name='index'",
    "columns": "SELECT * FROM table_columns('index')",
    "counts": 'SELECT count(*) rows,count_distinct(index_code) codes,min(import_time) first_import,max(import_time) last_import FROM "index"',
    "duplicate_codes": 'SELECT * FROM (SELECT index_code,count(*) rows FROM "index" GROUP BY index_code) WHERE rows>1 LIMIT 20',
    "samples": 'SELECT * FROM "index" ORDER BY index_code,import_time LIMIT 10',
    "catalog": 'SELECT * FROM "index" ORDER BY index_code,import_time LIMIT 5001',
    "version": "SELECT build()",
}
report = {"task_id": "D003", "observed_at": datetime.now(timezone.utc).isoformat(), "access": "read_only_http_select"}
for name, sql in queries.items():
    request = Request(endpoint + "?" + urlencode({"query": sql}), headers={"Authorization": "Basic " + auth})
    try:
        with urlopen(request, timeout=20) as response:
            data = json.load(response)
    except Exception as failure:
        raise RuntimeError(f"Read-only index query failed: {name}, {type(failure).__name__}") from None
    if "error" in data:
        raise RuntimeError(f"QuestDB rejected baseline query: {name}")
    columns = [column["name"] for column in data["columns"]]
    report[name] = {"query": sql, "rows": [dict(zip(columns, row)) for row in data["dataset"]]}
    if name == "catalog" and len(report[name]["rows"]) > 5000:
        raise RuntimeError("Catalog exceeds bounded 5000-row audit")
folder = root / "artifacts/java-migration/D003"
folder.mkdir(parents=True, exist_ok=True)
(folder / "physical-baseline.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"counts": report["counts"]["rows"], "columns": len(report["columns"]["rows"]),
                  "duplicate_code_samples": len(report["duplicate_codes"]["rows"])}, ensure_ascii=False))
