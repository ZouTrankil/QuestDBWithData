"""Pure protocol2 guards. No QuestDB, HTTP, native, or SQLite connections."""
from __future__ import annotations

import copy
import importlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
sut = importlib.import_module("prepare_d103_equity_style_increment_after_readonly_recovery")


def protocol():
    snapshot = {"table": "index_monthly", "tableId": 9, "directory": "index_monthly~9", "physicalTxn": 1, "sequenceTxn": 1, "schemaHash": "a" * 64}
    receipt = {"task_id": "D103", "stage": "readonly_recovery", "status": "VERIFIED_ISOLATED_INITIAL_BY_READONLY_RECOVERY",
        "new_ddl": 0, "new_dml": 0, "new_ilp_batches": 0, "new_materialization_runs": 0, "ledger_mutated": False, "formal_mutated": False,
        "fixture_receipt": str(sut.DIRECTORY / "pure-source.json"), "fixture_sha256": "c" * 64,
        "key_and_full_field_comparisons": 60, "exact_double_bit_comparisons": 58, "double_tolerance": 0,
        "source": {"rawRows": 32, "rawFingerprint": "b" * 64, "snapshot": snapshot}, "actual_target": {"rowCount": 2, "targetId": "d103-" + "d" * 64},
        "configured_read_group": {"members": [{"memberId": "styles", "datasetId": "equity_style_monthly", "definitionVersion": 1, "status": "READ", "page": {"rows": [{}, {}]}}]}}
    role_ids = {role: "run-" + role for role in sut.ROLES}
    ledger = {"runs": [], "entries": [], "events": [], "groups": [], "leases": []}
    operations = []
    for role in sut.ROLES:
        run_id = role_ids[role]
        state, count = sut.EXPECTED[role]
        group, child = role == "configured_write_group", role == "typed_write_child"
        mode = None if group else "INGEST" if child else "RECONCILE" if role == "readonly_reconcile" else "MATERIALIZE"
        parent = role_ids["configured_write_group"] if child else role_ids["cancelled_for_resume"] if role == "exact_resume" else None
        job = "group.prepared_writes" if group else "write.equity_style_monthly" if child else "data.equity_style_monthly"
        frozen = {} if group else {"mode": mode}
        if not group and not child:
            frozen.update({"from": "2026-06-01", "to": "2026-07-01", "parameters": {"bootstrap_from": "2026-06-01",
                "target_id": receipt["actual_target"]["targetId"], "source_hash": receipt["source"]["rawFingerprint"],
                "source_version": sut.source_version(snapshot)}})
        run = {"id": run_id, "job_id": job, "job_version": 1, "parent_run_id": parent, "target_id": "group-control" if group else receipt["actual_target"]["targetId"], "frozen_json": json.dumps(frozen)}
        payload = {"verification": {"passed": True, "expectedRows": count, "actualRows": count, "matchedRows": count, "mismatchedRows": 0, "duplicateKeys": 0, "missingKeys": 0}} if state == "VERIFIED" else {"errorCode": "CancellationException"}
        entry = {"id": run_id, "kind": "RUN", "run_id": run_id, "state": state, "payload_json": json.dumps(payload)}
        ledger["runs"].append(run); ledger["entries"].append(entry)
        ack_events = []
        if state == "VERIFIED" and not group:
            ledger["entries"].extend([{"id": "attempt-" + run_id, "kind": "ATTEMPT", "run_id": run_id, "state": "VERIFIED"},
                                      {"id": "slice-" + run_id, "kind": "SLICE", "run_id": run_id, "state": "VERIFIED"}])
            ack = {"entry_id": "slice-" + run_id, "revision": 5, "state": "ACKNOWLEDGED", "payload_json": "{}"}
            ledger["events"].append(ack); ack_events.append(ack)
        operations.append({"role": role, "run_id": run_id, "state": state, "verified_rows": count, "mode": mode,
                           "parent_run_id": parent, "ledger_entry": copy.deepcopy(entry), "acked_slice_receipts": copy.deepcopy(ack_events)})
    receipt.update(original_run_ids=[role_ids[role] for role in sut.ROLES], original_operations=operations)
    receipt["ledger_snapshot"] = copy.deepcopy(ledger)
    return receipt, ledger


class FreshReadonlyReceiptTests(unittest.TestCase):
    def validate(self, value):
        sut.validate_readonly_receipt(value, sut.DIRECTORY / "pure-source.json", "c" * 64)

    def test_fresh_zero_publication_complete_two_month_receipt(self):
        self.validate(protocol()[0])

    def test_original_failed_receipt_cannot_be_relabelled_as_new_success(self):
        value = protocol()[0]; value["status"] = "VERIFIED_ISOLATED_INITIAL_REPLAY"
        with self.assertRaises(RuntimeError):
            self.validate(value)

    def test_any_ddl_dml_ilp_run_or_ledger_change_refused(self):
        for key in ("new_ddl", "new_dml", "new_ilp_batches", "new_materialization_runs", "ledger_mutated", "formal_mutated"):
            value = protocol()[0]; value[key] = True if key.endswith("mutated") else 1
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                self.validate(value)

    def test_boolean_zero_counts_not_empty_proof(self):
        value = protocol()[0]; value["new_ilp_batches"] = False
        with self.assertRaises(RuntimeError):
            self.validate(value)

    def test_wrong_fixture_sha_and_raw_source_count_refused(self):
        for change in ("sha", "rows", "bits", "fields"):
            value = protocol()[0]
            if change == "sha":
                value["fixture_sha256"] = "e" * 64
            elif change == "rows":
                value["source"]["rawRows"] = 31
            else:
                value["exact_double_bit_comparisons" if change == "bits" else "key_and_full_field_comparisons"] -= 1
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                self.validate(value)

    def test_current_typed_group_must_be_real_read_two(self):
        value = protocol()[0]; value["configured_read_group"]["members"][0]["status"] = "CANCELLED"
        with self.assertRaises(RuntimeError):
            self.validate(value)


class OriginalLedgerRoleTests(unittest.TestCase):
    def test_eight_original_roles_true_terminal_metrics_and_parent_chains(self):
        receipt, ledger = protocol()
        result = sut.validate_original_operations(receipt, ledger)
        self.assertEqual((result["original_runs"], result["original_entries"]), (8, 18))
        self.assertIsNone(receipt["original_operations"][5]["mode"])

    def test_duplicate_missing_or_reordered_original_roles_refused(self):
        for mode in ("missing", "duplicate", "order"):
            receipt, ledger = protocol()
            if mode == "missing":
                receipt["original_operations"].pop()
            elif mode == "duplicate":
                receipt["original_operations"][-1]["role"] = "first"
            else:
                receipt["original_operations"].reverse()
            with self.subTest(mode=mode), self.assertRaises(RuntimeError):
                sut.validate_original_operations(receipt, ledger)

    def test_fresh_materialization_run_must_not_be_omitted(self):
        receipt, ledger = protocol(); ledger["runs"].append({"id": "new-write"})
        with self.assertRaises(RuntimeError):
            sut.validate_original_operations(receipt, ledger)

    def test_cancelled_run_cannot_claim_rows_or_have_slice(self):
        for changed in ("count", "slice"):
            receipt, ledger = protocol()
            if changed == "count":
                receipt["original_operations"][2]["verified_rows"] = 2
            else:
                ledger["entries"].append({"id": "bad-slice", "kind": "SLICE", "run_id": receipt["original_run_ids"][2], "state": "VERIFIED"})
            with self.subTest(changed=changed), self.assertRaises(RuntimeError):
                sut.validate_original_operations(receipt, ledger)

    def test_group_and_resume_parent_ambiguity_refused(self):
        for index in (3, 6):
            receipt, ledger = protocol(); receipt["original_operations"][index]["parent_run_id"] = None
            with self.subTest(index=index), self.assertRaises(RuntimeError):
                sut.validate_original_operations(receipt, ledger)

    def test_exact_frozen_mode_and_source_version_must_match(self):
        for field, wrong in (("mode", "RECONCILE"), ("source_version", "e" * 64), ("source_hash", "e" * 64), ("target_id", "formal")):
            receipt, ledger = protocol()
            frozen = json.loads(ledger["runs"][0]["frozen_json"])
            if field == "mode":
                frozen[field] = wrong
            else:
                frozen["parameters"][field] = wrong
            ledger["runs"][0]["frozen_json"] = json.dumps(frozen)
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_original_operations(receipt, ledger)

    def test_original_actual_run_payload_and_ack_event_maps_bound(self):
        for field in ("ledger_entry", "acked_slice_receipts"):
            receipt, ledger = protocol()
            if field == "ledger_entry":
                receipt["original_operations"][0][field]["payload_json"] = "{}"
            else:
                receipt["original_operations"][0][field] = []
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_original_operations(receipt, ledger)

    def test_boolean_verified_rows_not_long_metric(self):
        receipt, ledger = protocol(); receipt["original_operations"][6]["verified_rows"] = True
        with self.assertRaises(RuntimeError):
            sut.validate_original_operations(receipt, ledger)

    def test_original_full_key_verification_cannot_hide_mismatch(self):
        receipt, ledger = protocol()
        payload = json.loads(ledger["entries"][0]["payload_json"]); payload["verification"]["mismatchedRows"] = 1
        ledger["entries"][0]["payload_json"] = json.dumps(payload)
        receipt["original_operations"][0]["ledger_entry"] = copy.deepcopy(ledger["entries"][0])
        with self.assertRaises(RuntimeError):
            sut.validate_original_operations(receipt, ledger)


class ImmutableHistoryClosureTests(unittest.TestCase):
    def inputs(self):
        receipt, actual = protocol()
        historical = {"ledger_path": str(sut.LEDGER.resolve()), "run_ids": receipt["original_run_ids"],
            "all_entries_terminal": True, "retained_leases": 0, **copy.deepcopy(actual),
            "original_operations": [{key: copy.deepcopy(value) for key, value in row.items() if key != "acked_slice_receipts"} for row in receipt["original_operations"]]}
        return receipt, historical, actual

    def test_original_snapshot_and_fresh_current_all_five_maps_exact(self):
        receipt, historical, actual = self.inputs()
        self.assertTrue(sut.validate_original_frontier(receipt, historical, actual)["all_original_current_exact"])

    def test_event_group_or_run_change_blocks_after_terminal_even_zero_leases(self):
        for field in ("runs", "entries", "events", "groups", "leases"):
            receipt, historical, actual = self.inputs(); actual[field].append({"unexpected": True})
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_original_frontier(receipt, historical, actual)

    def test_fresh_snapshot_or_original_role_field_cannot_drift(self):
        receipt, historical, actual = self.inputs(); receipt["ledger_snapshot"]["events"] = []
        with self.assertRaises(RuntimeError):
            sut.validate_original_frontier(receipt, historical, actual)
        receipt, historical, actual = self.inputs(); receipt["original_operations"][0]["state"] = "FAILED"
        with self.assertRaises(RuntimeError):
            sut.validate_original_frontier(receipt, historical, actual)

    def test_original_ledger_path_and_run_ids_must_remain_scoped(self):
        for field, wrong in (("ledger_path", str(sut.audit.REPO / "var/other.sqlite3")), ("run_ids", ["different"]), ("retained_leases", False)):
            receipt, historical, actual = self.inputs(); historical[field] = wrong
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_original_frontier(receipt, historical, actual)

    def test_project_java_readonly_sha_binding_supported(self):
        path = sut.audit.REPO / "data-app/src/main/java/com/zoutrankil/data/derived/storage/EquityStyleMonthlyWritePort.java"
        proof = {"path": str(path), "sha256": sut.audit.digest(path)}
        self.assertEqual(sut.proof_file(proof), proof)
        proof["sha256"] = "e" * 64
        with self.assertRaises(RuntimeError):
            sut.proof_file(proof)


class KnownSerializationFailureTests(unittest.TestCase):
    def gate_files(self, directory):
        base = Path(directory)
        def text(name, content):
            path = base / name; path.write_text(content, encoding="utf-8")
            return {"path": str(path), "sha256": sut.audit.digest(path)}
        root = ET.Element("testsuite", {"name": "com.zoutrankil.data.config.EquityStyleMonthlyLiveAcceptanceTest", "tests": "1", "failures": "1", "errors": "0", "skipped": "0"})
        case = ET.SubElement(root, "testcase", {"name": "actualBoundedMaterializationUsesCanonicalRunnerTypedReadsAndComposition()"})
        failed = ET.SubElement(case, "failure", {"type": "com.fasterxml.jackson.databind.exc.InvalidDefinitionException", "message": "java.time.YearMonth unsupported"})
        failed.text = "at EquityStyleMonthlyLiveAcceptanceTest.actual(EquityStyleMonthlyLiveAcceptanceTest.java:177)"
        ET.SubElement(root, "system-out").text = "d103-private - Shutdown completed.\nd103-formal - Shutdown completed."
        gate = {"task_id": "D103", "decision": "accepted_for_readonly_receipt_recovery", "original_jvm_pid_birth_not_recorded": True,
            "retry_forbidden": True, "all_original_operations_finished_before_serialization_failure": True,
            "sender_ownership": {field: True for field in ("synchronous_flush", "auto_flush_disabled", "retry_timeout_zero", "close_in_finally", "no_business_writer_child")},
            "failed_junit": text("failed.xml", ET.tostring(root, encoding="unicode")), "log": text("failed.log", "original exit1"),
            "executed_test_source": text("test.java", "\n" * 176 + "Files.writeString(output, mapper.writeValueAsString(evidence));\n"),
            "port_source": text("port.java", "@Override public synchronized void send(Object rows) { try { sender.flush(); } finally { sender.reset(); sender.close(); senderStopped = true; senderActive = false; } catchX { senderStopped = false; senderActive = true; unresolved = true; } } public static void requireBatch(Object rows) {} factory.disableAutoFlush().retryTimeoutMillis(0).build();"),
            "adapter_source": text("adapter.java", "frozen adapter"), "runner_source": text("runner.java", "frozen runner"),
            "typed_adapter_source": text("typed.java", "frozen typed adapter"),
            "original_executor_completion": text("executor.json", '{"exit_code":1}'), "ledger_snapshot": text("ledger.json", "{}"),
            "create_claim": text("create.json", "{}"), "initial_source_recovery": text("source.json", "{}")}
        return gate

    def consume(self, gate, base):
        path = Path(base) / "gate.json"; sut.audit.save_new(path, gate)
        with patch.object(sut, "ORIGINAL_GATE", path):
            return sut.validate_original_failure({"path": str(path), "sha256": sut.audit.digest(path)})

    def test_exact_last_serialization_failure_preserves_unknown_original_pid(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as base:
            gate = self.gate_files(base)
            _, _, scope = self.consume(gate, base)
            self.assertTrue(scope["original_jvm_pid_birth_not_recorded"])
            self.assertFalse(scope["original_native_absence_claimed"])

    def test_original_unknown_birth_must_not_be_relabelled_as_native_stop(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as base:
            gate = self.gate_files(base); gate["original_jvm_pid_birth_not_recorded"] = False
            with self.assertRaises(RuntimeError):
                self.consume(gate, base)

    def test_any_unresolved_sender_ownership_blocks_new_source_append(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as base:
            gate = self.gate_files(base); gate["sender_ownership"]["close_in_finally"] = False
            with self.assertRaises(RuntimeError):
                self.consume(gate, base)

    def test_other_failure_line_or_missing_completed_pool_shutdown_refused(self):
        for changed in ("line", "pool"):
            with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as base:
                gate = self.gate_files(base)
                path = Path(gate["failed_junit"]["path"]); content = path.read_text()
                content = content.replace("java:177", "java:149") if changed == "line" else content.replace("d103-private - Shutdown completed.", "")
                path.write_text(content); gate["failed_junit"]["sha256"] = sut.audit.digest(path)
                with self.subTest(changed=changed), self.assertRaises(RuntimeError):
                    self.consume(gate, base)

    def test_removed_finally_close_and_original_exit_zero_refused(self):
        for changed in ("close", "exit"):
            with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as base:
                gate = self.gate_files(base); key = "port_source" if changed == "close" else "original_executor_completion"
                path = Path(gate[key]["path"])
                path.write_text(path.read_text().replace("sender.reset(); sender.close();", "sender.close();") if changed == "close" else '{"exit_code":0}')
                gate[key]["sha256"] = sut.audit.digest(path)
                with self.subTest(changed=changed), self.assertRaises(RuntimeError):
                    self.consume(gate, base)

    def test_no_execute_flag_never_connects_or_submits(self):
        with patch.object(sut, "admission_inputs") as admission, self.assertRaises(RuntimeError):
            sut.run(MagicMock(execute_isolated=False), None, {})
        admission.assert_not_called()


if __name__ == "__main__":
    unittest.main()
