"""Archive the root-run pure guards; no service, process, or database access."""
import ast
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
script = repo / "tools/audit_d102_etf_view.py"
tests = repo / "tools/test_d102_script_guards.py"
log = folder / "python-identity-guards-20261006.log"
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(script) == "841fa6ff015ef8d584b5d1e861da615670adc55583f799727ec41ffeaca89300"
assert sha(tests) == "69eb4ac1e80c91fd711c4c945cc65fa6bd4b2554cb0249a624d0a662bb6cf35f"
body = log.read_text(encoding="utf-8")
assert re.search(r"Ran 59 tests in .*\s+OK\s*$", body)
assert not re.search(r"\.\.\. (FAIL|ERROR|skipped)", body)
tree = ast.parse(tests.read_text(encoding="utf-8-sig"))
methods = [f"{cls.name}.{method.name}" for cls in tree.body if isinstance(cls, ast.ClassDef)
           for method in cls.body if isinstance(method, ast.FunctionDef) and method.name.startswith("test_")]
assert len(methods) == len(set(methods)) == 59
result = {"task_id": "D102", "status": "PASSED", "checked_at": datetime.now(timezone.utc).isoformat(),
          "python_unique": 59, "failures": 0, "errors": 0, "skipped": 0, "methods": methods,
          "script_sha256": sha(script), "tests_sha256": sha(tests), "log_sha256": sha(log),
          "counting": "Final 59 definitions replace the previous 39; reruns are not added"}
with (folder / "python-identity-guards-20261006.json").open("x", encoding="utf-8") as output:
    json.dump(result, output, ensure_ascii=False, indent=2)
    output.write("\n")
print(json.dumps({"status": result["status"], "python_unique": 59}))
