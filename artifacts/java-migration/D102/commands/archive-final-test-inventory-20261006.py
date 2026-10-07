"""Archive distinct passed methods from immutable actual reports, not run totals."""
import ast
import hashlib
import json
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
methods, reports = {}, []
pure = folder / "java-pure-tests-xml-20261006"
deadline = folder / "java-metadata-deadline-guards-xml-20261006"
live = folder / "java-view-read-acceptance-xml-20261006"
assert len(list(pure.glob("TEST-*.xml"))) == 14
assert len(list(deadline.glob("TEST-*.xml"))) == 1
assert len(list(live.glob("TEST-*.xml"))) == 1
for stage, directory in [("pure", pure), ("deadline", deadline), ("live", live)]:
    for path in sorted(directory.glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        assert all(int(root.get(field, "0")) == 0 for field in ("failures", "errors", "skipped"))
        cases = root.findall("testcase")
        assert len(cases) == int(root.get("tests"))
        assert all(not list(case) for case in cases), "Only passed actual methods may enter inventory"
        stage_methods = {(case.get("classname"), case.get("name")) for case in cases}
        assert len(stage_methods) == len(cases)
        if stage == "deadline":
            prior = {key for key in methods if key[0] == root.get("name")}
            assert len(prior) == 23 and len(stage_methods) == 24 and prior < stage_methods
        for key in stage_methods:
            methods[key] = {"classname": key[0], "method": key[1], "latest_report": str(path.relative_to(repo)), "stage": stage}
        reports.append({"path": str(path.relative_to(repo)), "sha256": sha(path), "executed_methods": len(cases), "stage": stage})
assert len(methods) == 130
assert sum(value["stage"] == "live" for value in methods.values()) == 1
guards_path = folder / "python-historical-identity-leaf-guards-20261006.json"
guards = json.loads(guards_path.read_text(encoding="utf-8"))
assert guards["status"] == "PASSED" and all(guards[field] == 0 for field in ("failures", "errors", "skipped"))
test_path = repo / "tools/test_d102_script_guards.py"
tree = ast.parse(test_path.read_text(encoding="utf-8-sig"))
python_methods = [f"{cls.name}.{method.name}" for cls in tree.body if isinstance(cls, ast.ClassDef)
                  for method in cls.body if isinstance(method, ast.FunctionDef) and method.name.startswith("test_")]
assert len(python_methods) == len(set(python_methods)) == guards["python_unique"]
assert set(python_methods) == set(guards["methods"])
assert sha(test_path) == guards["tests_sha256"]
assert sha(repo / "tools/audit_d102_etf_view.py") == guards["script_sha256"]
result = {"task_id": "D102", "checked_at": datetime.now(timezone.utc).isoformat(), "status": "PASSED_UNIQUE_TEST_INVENTORY",
          "java_unique": 130, "java_unique_pure": 129, "java_live": 1,
          "python_unique": len(python_methods), "failures": 0, "errors": 0, "skipped": 0,
          "java_methods": [methods[key] for key in sorted(methods)], "java_reports": reports,
          "python_methods": python_methods, "python_report": {"path": str(guards_path.relative_to(repo)), "sha256": sha(guards_path)},
          "counting": "Latest metadata guard 24 subsumes earlier 23; Python final source cases replace earlier 39/59; failed pre-CREATE audits are retained outside passed-method counts"}
with (folder / "unique-test-inventory-final-20261006.json").open("x", encoding="utf-8") as output:
    json.dump(result, output, ensure_ascii=False, indent=2)
    output.write("\n")
print(json.dumps({key: result[key] for key in ("status", "java_unique", "python_unique")}))
