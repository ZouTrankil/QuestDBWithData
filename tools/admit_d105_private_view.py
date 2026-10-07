"""Root's file-only admission for one missing private D105 ordinary VIEW.

No native calls, database connection, source writer or retry exists here.
Actual read-only prerequisites and current independent tool review are required.
"""
from __future__ import annotations

import argparse
import ast
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
import prepare_d105_macro_core_view_isolated as creator

audit = creator.audit
D = audit.DIRECTORY
OUTPUT = D / "coordinator-private-view-create-admission-20261007.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--independent-review", type=Path, required=True)
    args = parser.parse_args()
    audit.require(not OUTPUT.exists(), "New-only D105 admission identity required")
    data = audit.prerequisites()
    private_path = D / "view-isolated-preflight-20261007.json"
    formal_path = audit.FORMAL_OUTPUT
    private, formal = (audit.load(path, audit.digest(path)) for path in (private_path, formal_path))
    audit.require(private["status"] == "VERIFIED_ISOLATED_VIEW_ABSENCE_PREFLIGHT" and formal["status"] == "VERIFIED_FORMAL_VIEW_READ", "Both actual read-only audits required")
    audit.require(private["script_sha256"] == audit.digest(audit.__file__), "Private audit must use current frozen script")
    audit.require(formal["script_sha256"] == "9cb825bd97ef48efdb6aa0ceda21395ca67c254833ba9c2baf8ba2f4f506b87b", "Original actual formal script identity required")
    catalog_path = D / "java-catalog-startup-20261007.json"
    catalog = audit.load(catalog_path, audit.digest(catalog_path))
    audit.require(catalog["task_id"] == "D105" and catalog["dataset_count"] == 54 and catalog["job_count"] == 42 and catalog["view_independent_jobs"] == catalog["database_connections"] == 0 and catalog["ledger_created"] is False, "Actual startup/no competing owner proof required")
    xmls = sorted((D / "java-pure-tests-import-correction-20261007").glob("TEST-*.xml"))
    audit.require(len(xmls) == 5, "Five actual D105 pure suites required")
    methods = set()
    for path in xmls:
        suite = ET.parse(path).getroot()
        audit.require(all(suite.attrib[key] == "0" for key in ("failures", "errors", "skipped")), "Every archived Java test must pass")
        for case in suite.findall("testcase"):
            key = (case.attrib["classname"], case.attrib["name"])
            audit.require(key not in methods, "No duplicate Java method count")
            methods.add(key)
    audit.require(len(methods) == 60, "Exactly 60 unique D105 pure Java methods required")
    guards_log = D / "python-view-guards-private-identity-correction-20261007.log"
    log = guards_log.read_text(encoding="utf-8-sig")
    audit.require("Ran 44 tests in " in log and log.rstrip().endswith("OK") and "... FAIL" not in log and "... ERROR" not in log, "Actual final 44 Python guards PASS required")
    methods_python = [(node.name, fn.name) for node in ast.parse(creator.GUARDS.read_text(encoding="utf-8-sig")).body if isinstance(node, ast.ClassDef) for fn in node.body if isinstance(fn, ast.FunctionDef) and fn.name.startswith("test_")]
    audit.require(len(methods_python) == len(set(methods_python)) == 44, "Exactly 44 unique frozen Python methods required")
    gate = {
        "protocol_version": 1, "task_id": "D105", "decision": "accepted_for_private_view_create_once",
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "new_DDL": 1, "new_DML": 0, "new_ILP": 0, "base_writes": 0, "formal_writes": 0,
        "source_writes": 0, "automatic_retry": False,
        "audit": audit.binding(private_path), "formal_audit": audit.binding(formal_path),
        "script": audit.binding(creator.SCRIPT), "independent_static_review": audit.binding(args.independent_review),
        "target": private["private_target_attestation"], "private_view": audit.PRIVATE_VIEW,
        "base": audit.PRIVATE_BASE, "owner_contract": private["owner_contract"],
        "d104_evidence": private["d104_evidence"],
        "java_pure_unique_PASS": 60, "python_pure_unique_PASS": 44,
        "pure_junit_bindings": [audit.binding(path) for path in xmls],
        "catalog": audit.binding(catalog_path), "python_guards_log": audit.binding(guards_log),
        "pure_java_executor": {"session_id": 24454, "chunk_id": "e25f14", "exit_code": 0},
        "python_guards_executor": {"chunk_id": "6de455", "exit_code": 0},
        "formal_audit_executor": {"chunk_id": "d47864", "exit_code": 0},
        "private_audit_executor": {"session_id": 91248, "chunk_id": "fc565d", "exit_code": 0},
        "formal_audit_private_identity_adaptation_note": audit.binding(D / "formal-audit-private-identity-adaptation-note-20261007.json"),
        "preserved_compile_failure": audit.binding(D / "java-pure-tests-20261007.log"),
        "coordinator_mode": "direct_local_serial", "human_comparison_status": "pending_review",
        "FULL_admitted": False, "formal_replacement_admitted": False, "D106_admitted": False,
        "invocation_scope": "One missing-only private ordinary identity VIEW CREATE. No business rows, ledger, formal objects or reference files may be written. Any UNKNOWN claim prevents resend."
    }
    creator.validate_code(gate)
    audit.require(gate["target"] == data["results"]["private_target"], "Actual predecessor target differs")
    audit.save_new(OUTPUT, gate)
    # The actual creator's complete gate consumer is checked without DB/native IO.
    creator.admission_inputs(argparse.Namespace(create_admission=OUTPUT, create_admission_sha256=audit.digest(OUTPUT)))
    print(json.dumps({"status": "VERIFIED_FILE_ONLY_PRIVATE_VIEW_ADMISSION", **audit.binding(OUTPUT)}))


if __name__ == "__main__":
    main()
