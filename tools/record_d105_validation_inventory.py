"""D105 file-only unique validation census, after both actual Java READ scopes.

Parses archived XML and frozen stdout; executes no test, database or native call.
No task registration changes. Historical failures and subset runs stay separate.
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
D = ROOT / "artifacts/java-migration/D105/commands"
OUTPUT = D / "validation-inventory-20261007.json"
PURE_DIRECTORY = D / "java-pure-tests-import-correction-20261007"
GUARDS = ROOT / "tools/test_d105_view_script_guards.py"
GUARDS_SHA = "f6062bcc4985b3c5cb1717c73c79d5400c690887c48806d89be1dde7691a5113"
GUARDS_LOG = D / "python-view-guards-private-identity-correction-20261007.log"
GUARDS_LOG_SHA = "1dce52a154fb93ac664f87df35286bc76fcbfcf58ecb61785e1e9d9d82eb295d"
COMPILE_FAILURE = D / "java-pure-tests-20261007.log"
COMPILE_FAILURE_SHA = "3da8fa536d3e7d7237da40e4150d932b0bff7120815b75da94e9188505003b7b"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def binding(path):
    path = Path(path).resolve(strict=True)
    require(path.is_relative_to(ROOT.resolve()) and path.is_file(), "Only actual project validation files allowed")
    return {"path": str(path), "sha256": digest(path)}


def exact_file(path, sha):
    require(re.fullmatch("[0-9a-f]{64}", sha) is not None and digest(path) == sha, "Exact frozen validation SHA differs")
    return binding(path)


def suite_cases(path, phase):
    suite = ET.parse(path).getroot()
    require(suite.tag == "testsuite" and all(suite.get(key) == "0" for key in ("failures", "errors", "skipped")), "Actual suite must have zero failure/error/skip")
    cases = suite.findall("testcase")
    require(re.fullmatch("[0-9]+", suite.get("tests", "")) and int(suite.get("tests")) == len(cases) and cases, "Actual complete XML testcase count required")
    rows, seen = [], set()
    for case in cases:
        require(not any(case.findall(key) for key in ("failure", "error", "skipped")), "Actual individual case is not PASS")
        key = (case.get("classname"), case.get("name"))
        require(all(isinstance(value, str) and value.strip() for value in key) and key not in seen, "Unique actual classname/method required in suite")
        seen.add(key)
        rows.append({"phase": phase, "class": key[0], "method": key[1], "report": binding(path)})
    return rows


def record(args):
    pure_paths = sorted(PURE_DIRECTORY.glob("TEST-*.xml"))
    require(len(pure_paths) == 5, "Exactly five archived current pure suites required")
    pure_rows = [case for path in pure_paths for case in suite_cases(path, "pure")]
    pure = {(row["class"], row["method"]) for row in pure_rows}
    require(len(pure) == len(pure_rows) == 60, "Exactly sixty unique current pure methods required")
    live_rows, live_paths, live_receipts = [], [], {}
    for scope, sha in (("private", args.private_live_sha256), ("formal", args.formal_live_sha256)):
        path = D / f"java-view-read-{scope}-acceptance-20261007.json"
        proof = exact_file(path, sha)
        receipt = json.loads(path.read_text(encoding="utf-8-sig"))
        require(receipt.get("task_id") == "D105" and receipt.get("scope") == scope and receipt.get("status") == "VERIFIED_" + scope.upper() + "_BOUNDED_VIEW_READ", "Actual successful D105 scope receipt required")
        require(all(type(receipt.get(key)) is int and receipt[key] == 0 for key in ("DDL", "DML", "ILP", "base_writes", "formal_writes")) and receipt.get("ledger_initialized") is False and receipt.get("automatic_retry") is False and receipt.get("actual_source_increment_during_D105") is False and receipt.get("actual_source_revision_during_D105") is False, "Actual live acceptance is SELECT only and reuses the prior D104 increment")
        reports = sorted((D / f"java-view-read-{scope}-result-20261007").glob("TEST-*.xml"))
        require(len(reports) == 1, "Exactly one actual live suite per scope required")
        cases = suite_cases(reports[0], scope)
        require(len(cases) == 1, "One actual live invocation per scope required")
        live_rows.extend(cases); live_paths.extend(reports); live_receipts[scope] = proof
    live = {(row["class"], row["method"]) for row in live_rows}
    require(len(live) == 1 and not pure & live and len(pure | live) == 61 and len(pure_rows + live_rows) == 62, "Two actual scopes must be one unique live method; no duplicate pure count")
    guard_source, guard_log = exact_file(GUARDS, GUARDS_SHA), exact_file(GUARDS_LOG, GUARDS_LOG_SHA)
    methods = [node.name + "." + fn.name for node in ast.parse(GUARDS.read_text(encoding="utf-8-sig")).body if isinstance(node, ast.ClassDef) for fn in node.body if isinstance(fn, (ast.FunctionDef, ast.AsyncFunctionDef)) and fn.name.startswith("test_")]
    text = GUARDS_LOG.read_text(encoding="utf-8-sig")
    counts = re.findall(r"Ran ([0-9]+) tests in ", text)
    require(len(methods) == len(set(methods)) == 44 and counts == ["44"] and text.rstrip().endswith("OK") and "... FAIL" not in text and "... ERROR" not in text, "Exact frozen forty-four actual Python guards required")
    failure = exact_file(COMPILE_FAILURE, COMPILE_FAILURE_SHA)
    require("BUILD FAILED" in COMPILE_FAILURE.read_text(encoding="utf-8-sig"), "Original compile failure must stay preserved")
    return {"protocol_version": 1, "task_id": "D105", "status": "VERIFIED_CURRENT_UNIQUE_TEST_INVENTORY", "checked_at": datetime.now(timezone.utc).isoformat(),
            "java_unique_passing_methods": 61, "java_pure_unique_methods": 60, "java_live_unique_methods": 1, "java_live_actual_stage_invocations": 2, "java_PASS_invocations": 62,
            "python_unique_pure_guards": 44, "current_inventory_failures": 0, "current_inventory_errors": 0, "current_inventory_skips": 0,
            "java_reports": [binding(path) for path in pure_paths + live_paths], "java_cases": pure_rows + live_rows, "actual_live_receipts": live_receipts,
            "python": [{"guard_source": guard_source, "actual_log": guard_log, "unique_PASS": 44, "methods": methods}],
            "historical_failures_preserved": [{"kind": "INITIAL_JAVA_TEST_IMPORT_COMPILE_FAILURE", "log": failure, "resolution": "Corrected imports compiled and the separately archived actual current sixty pure methods passed; the failed compile log is excluded from passing test counts."}],
            "historical_subset_runs_excluded": [{"kind": "ORIGINAL_43_GUARDS_SUBSET", "actual_log": binding(D / "python-view-guards-20261007.log"), "included_in_current_44_not_added": True}],
            "test_execution_owner": "root; two real SELECT scopes reuse the same unique JUnit method", "inventory_tool_executed_tests": 0, "formal_writes": 0, "source_writes": 0, "output_writes": 0,
            "actual_source_increment": False, "actual_source_revision": False, "next_task_admitted": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-live-sha256", required=True)
    parser.add_argument("--formal-live-sha256", required=True)
    args = parser.parse_args()
    require(not OUTPUT.exists(), "CREATE_NEW final validation inventory required")
    result = record(args)
    with OUTPUT.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(binding(OUTPUT)))


if __name__ == "__main__":
    main()
