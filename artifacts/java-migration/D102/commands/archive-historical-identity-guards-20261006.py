"""Archive the actual final 74-case pure test log; no service access."""
import ast
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
script, tests = repo / "tools/audit_d102_etf_view.py", repo / "tools/test_d102_script_guards.py"
log = folder / "python-historical-identity-guards-20261006.log"
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(script) == "60d0b3e26e3b685c6b08cf1e769ddd51999254e499361be9ff0f050c904a6e4c"
assert sha(tests) == "577d759ddd1231c68e3e0ed16f3f37e0e7af2155ebb4b8d543ebc6117cd72090"
body = log.read_text(encoding="utf-8")
assert re.search(r"Ran 74 tests in .*\s+OK\s*$", body)
assert not re.search(r"\.\.\. (FAIL|ERROR|skipped)", body)
tree = ast.parse(tests.read_text(encoding="utf-8-sig"))
methods = [f"{cls.name}.{method.name}" for cls in tree.body if isinstance(cls, ast.ClassDef)
           for method in cls.body if isinstance(method, ast.FunctionDef) and method.name.startswith("test_")]
assert len(methods) == len(set(methods)) == 74
result = {"task_id": "D102", "status": "PASSED", "checked_at": datetime.now(timezone.utc).isoformat(),
          "python_unique": 74, "failures": 0, "errors": 0, "skipped": 0, "methods": methods,
          "script_sha256": sha(script), "tests_sha256": sha(tests), "log_sha256": sha(log),
          "counting": "Final 74 cases subsume 39 baseline and 59 protocol1 cases; reruns are not added"}
with (folder / "python-historical-identity-guards-20261006.json").open("x", encoding="utf-8") as output:
    json.dump(result, output, ensure_ascii=False, indent=2)
    output.write("\n")
print(json.dumps({"status": result["status"], "python_unique": 74}))
