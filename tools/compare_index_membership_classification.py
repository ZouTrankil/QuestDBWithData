"""D005 read-only comparison of a saved SW2021 L2 source receipt and current QuestDB codes."""
from pathlib import Path
from datetime import datetime, timezone
from urllib.request import Request, urlopen
from urllib.parse import urlencode
import base64
import hashlib
import json
import sys
import yaml

root = Path(__file__).resolve().parents[1]
source_path = Path(sys.argv[1]).resolve()
if not source_path.is_file() or root not in source_path.parents:
    raise SystemExit("A saved workspace source receipt is required")
source_bytes = source_path.read_bytes()
source = json.loads(source_bytes)
if source.get("endpoint") != "index_classify" or source.get("parameters") != {"level": "L2", "src": "SW2021"}:
    raise SystemExit("Wrong classification receipt")
source_codes = [row["index_code"] for row in source["rows"]]
if len(source_codes) != len(set(source_codes)):
    raise SystemExit("Duplicate source classification code")

config = yaml.safe_load((root / "data-app/src/main/resources/application.yml").read_text(encoding="utf-8"))["app"]["questdb"]
auth = base64.b64encode(f"{config['username']}:{config['password']}".encode()).decode()
endpoint = f"http://{config['host']}:{config['qwp-port']}/exec"
sql = "SELECT DISTINCT index_code FROM index_member ORDER BY index_code LIMIT 2000"
request = Request(endpoint + "?" + urlencode({"query": sql}), headers={"Authorization": "Basic " + auth})
with urlopen(request, timeout=20) as response:
    content = response.read(8 * 1024 * 1024 + 1)
if len(content) > 8 * 1024 * 1024:
    raise SystemExit("Bounded QuestDB response exceeded 8 MiB")
data = json.loads(content)
if "error" in data or [column["name"] for column in data["columns"]] != ["index_code"]:
    raise SystemExit("Unexpected QuestDB response")
target_codes = [row[0] for row in data["dataset"]]
if len(target_codes) >= 2000 or len(target_codes) != len(set(target_codes)):
    raise SystemExit("Target classification bound or uniqueness failed")
source_set, target_set = set(source_codes), set(target_codes)
result = {
    "observedAt": datetime.now(timezone.utc).isoformat(),
    "access": "read_only_http_select",
    "sourceReceipt": str(source_path.relative_to(root)).replace("\\", "/"),
    "sourceReceiptSha256": hashlib.sha256(source_bytes).hexdigest(),
    "targetQuery": sql,
    "sourceL2Count": len(source_set),
    "targetL2Count": len(target_set),
    "sourceOnly": sorted(source_set - target_set),
    "targetOnly": sorted(target_set - source_set),
    "questdbWrites": 0,
}
output = root / "artifacts/java-migration/D005/classification-coverage.json"
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: result[k] for k in ("sourceL2Count", "targetL2Count", "sourceOnly", "targetOnly")}, ensure_ascii=False))
