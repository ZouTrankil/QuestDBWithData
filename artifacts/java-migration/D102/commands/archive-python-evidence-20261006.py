"""Archive root's new-only pure log with explicit expected code hashes and case count."""
import ast
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

prefix, expected_script, expected_tests, expected_count = sys.argv[1:]
count = int(expected_count)
assert re.fullmatch(r"[a-z0-9-]+", prefix) and count >= 74
repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
script, tests = repo / "tools/audit_d102_etf_view.py", repo / "tools/test_d102_script_guards.py"
log = folder / (prefix + ".log")
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(script) == expected_script and sha(tests) == expected_tests
body = log.read_text(encoding="utf-8")
assert re.search(rf"Ran {count} tests in .*\s+OK\s*$", body)
assert not re.search(r"\.\.\. (FAIL|ERROR|skipped)", body)
tree = ast.parse(tests.read_text(encoding="utf-8-sig"))
methods = [f"{cls.name}.{method.name}" for cls in tree.body if isinstance(cls, ast.ClassDef)
           for method in cls.body if isinstance(method, ast.FunctionDef) and method.name.startswith("test_")]
assert len(methods) == len(set(methods)) == count
result = {"task_id": "D102", "status": "PASSED", "checked_at": datetime.now(timezone.utc).isoformat(),
          "python_unique": count, "failures": 0, "errors": 0, "skipped": 0, "methods": methods,
          "script_sha256": sha(script), "tests_sha256": sha(tests), "log_sha256": sha(log),
          "counting": "Final unique definitions replace earlier source versions; reruns are not added"}
with (folder / (prefix + ".json")).open("x", encoding="utf-8") as output:
    json.dump(result, output, ensure_ascii=False, indent=2)
    output.write("\n")
print(json.dumps({"status": "PASSED", "python_unique": count}))
