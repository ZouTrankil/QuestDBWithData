"""Pure D103 increment guards; mock all QuestDB, HTTP, native and SQLite I/O."""
from __future__ import annotations

import base64
import copy
import importlib
import json
import math
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
sut = importlib.import_module("prepare_d103_equity_style_increment_isolated")


def rows(month="202608"):
    data = []
    for index, code in enumerate(sut.audit.FEATURE_CODES.values()):
        row = {field: None for field in sut.audit.SOURCE_FIELDS}
        row.update(ts_code=code, trade_date=f"{month[:4]}-{month[4:]}-26T00:00:00Z", pct_chg=float(index), layer="macro_core", bucket="broad_base")
        data.append(row)
    return sorted(data, key=lambda row: (row["trade_date"], row["ts_code"]))


def state():
    return {"exists": True, "settled": True, "actual_select_count": 2,
        "physical": {"id": 10, "directoryName": "java_d103_equity_style_monthly_acceptance~10", "table_txn": 4, "table_row_count": 2,
                     "wal_pending_row_count": 0, "table_suspended": False},
        "wal": {"sequencerTxn": 4, "writerTxn": 4, "bufferedTxnSize": 0, "suspended": False}}


def snapshot():
    return {"tableId": 10, "directory": "java_d103_equity_style_monthly_acceptance~10", "physicalTxn": 4, "sequenceTxn": 4,
        "writerTxn": 4, "pendingRows": 0, "bufferedTxns": 0, "suspended": False, "rowCount": 2, "metadataRowCount": 2,
        "schemaHash": "a" * 64, "targetId": "questdb-" + "b" * 64}


def ledger_rows():
    return {"runs": [{"id": "run", "job_id": "data.equity_style_monthly", "job_version": 1, "target_id": snapshot()["targetId"]}],
            "entries": [{"id": "run", "kind": "RUN", "run_id": "run", "state": "VERIFIED"},
                        {"id": "slice", "kind": "SLICE", "run_id": "run", "state": "VERIFIED"}], "leases": []}


def connection(data):
    value = MagicMock()
    def execute(sql):
        cursor = MagicMock()
        mapping = {"sync_runs": "runs", "sync_entries": "entries", "sync_interval_locks": "leases"}
        key = next((key for table, key in mapping.items() if f"FROM {table} " in sql), None)
        cursor.fetchall.return_value = data[key] if key else []
        return cursor
    value.execute.side_effect = execute
    return value


class SourceAndAckTests(unittest.TestCase):
    def test_exact_august_insert_all14_columns(self):
        sql = sut.source_insert_sql(rows())
        self.assertTrue(sql.startswith("INSERT INTO index_monthly (" + ",".join(sut.audit.SOURCE_FIELDS) + ") VALUES "))
        self.assertNotIn("equity_style_monthly", sql)
        self.assertNotIn(";", sql)

    def test_initial_month_cannot_be_resent(self):
        with self.assertRaises(RuntimeError):
            sut.source_insert_sql(rows("202606"))

    def test_missing_code_cannot_be_declared_complete(self):
        with self.assertRaises(RuntimeError):
            sut.source_insert_sql(rows()[1:])

    def test_duplicate_full_key_rejected(self):
        value = rows(); value[-1] = copy.deepcopy(value[0])
        with self.assertRaises(RuntimeError):
            sut.source_insert_sql(value)

    def test_missing_full_field_rejected(self):
        value = rows(); del value[0]["close"]
        with self.assertRaises(RuntimeError):
            sut.source_insert_sql(value)

    def test_all_null_return_code_rejected(self):
        value = rows(); value[0]["pct_chg"] = None
        with self.assertRaises(RuntimeError):
            sut.source_insert_sql(value)

    def test_nonfinite_and_integer_double_rejected(self):
        for wrong in (math.inf, math.nan, 1, False):
            value = rows(); value[0]["close"] = wrong
            with self.subTest(wrong=wrong), self.assertRaises(RuntimeError):
                sut.source_insert_sql(value)

    def test_exact_dml_ack_supported(self):
        sut.validate_dml_ack({"dml": "OK"})
        sut.validate_dml_ack({"dml": "OK", "updated": 16})

    def test_ddl_ack_never_proves_insert_delivery(self):
        with self.assertRaises(RuntimeError):
            sut.validate_dml_ack({"ddl": "OK"})

    def test_error_extra_unknown_and_wrong_rows_refused(self):
        for value in ({}, [], {"dml": "OK", "error": "bad"}, {"dml": "OK", "ddl": "OK"},
                      {"dml": "OK", "updated": True}, {"dml": "OK", "updated": 15}, {"dml": "ok"}):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.validate_dml_ack(value)

    def test_signed_zero_and_ulp_strict_source_comparison(self):
        expected = rows()
        for wrong in (-0.0, math.nextafter(0.0, math.inf), None):
            value = copy.deepcopy(expected); value[0]["pct_chg"] = wrong
            with self.subTest(wrong=wrong), self.assertRaises(RuntimeError):
                sut.fixture.strict_source_compare(value, expected)


class ScopedNativeAndSnapshotTests(unittest.TestCase):
    PID, BIRTH, STOP = 12345, "2026-10-06T15:00:00.1234567Z", "2026-10-06T15:02:00.0000000Z"

    def test_original_pid_absent_proves_only_scoped_original_stop(self):
        result = sut.validate_original_java_absent([], self.PID, self.BIRTH, self.STOP)
        self.assertFalse(result["original_identity_present"])

    def test_same_original_birth_present_refused(self):
        with self.assertRaises(RuntimeError):
            sut.validate_original_java_absent([{"pid": self.PID, "birth": self.BIRTH}], self.PID, self.BIRTH, self.STOP)

    def test_pid_reuse_requires_birth_after_complete_stop(self):
        value = sut.validate_original_java_absent([{"pid": self.PID, "birth": "2026-10-06T15:03:00Z"}], self.PID, self.BIRTH, self.STOP)
        self.assertFalse(value["original_identity_present"])
        with self.assertRaises(RuntimeError):
            sut.validate_original_java_absent([{"pid": self.PID, "birth": "2026-10-06T15:01:00Z"}], self.PID, self.BIRTH, self.STOP)

    def test_unknown_missing_non_utc_and_different_pid_refused(self):
        for value in ({"pid": self.PID}, {"pid": self.PID, "birth": "UNKNOWN"}, {"pid": self.PID, "birth": "2026-10-06T23:03:00+08:00"},
                      {"pid": self.PID + 1, "birth": "2026-10-06T15:03:00Z"}):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.validate_original_java_absent([value], self.PID, self.BIRTH, self.STOP)

    def test_boolean_pid_and_multiple_native_matches_refused(self):
        with self.assertRaises(RuntimeError):
            sut.validate_original_java_absent([], True, self.BIRTH, self.STOP)
        with self.assertRaises(RuntimeError):
            sut.validate_original_java_absent([{}, {}], self.PID, self.BIRTH, self.STOP)

    def test_target_frontier_actual_fields_match_java(self):
        sut.validate_java_snapshot(state(), snapshot())

    def test_target_txn_id_directory_counts_and_raw_null_drift_refused(self):
        for field, value in (("tableId", 11), ("directory", "other"), ("physicalTxn", 5), ("sequenceTxn", 5),
                             ("writerTxn", 3), ("rowCount", 3), ("metadataRowCount", None)):
            snap = snapshot(); snap[field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_java_snapshot(state(), snap)

    def test_snapshot_boolean_counter_missing_schema_and_suspension_refused(self):
        for field, value in (("tableId", True), ("schemaHash", "UNKNOWN"), ("suspended", True)):
            snap = snapshot(); snap[field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_java_snapshot(state(), snap)
        snap = snapshot(); del snap["physicalTxn"]
        with self.assertRaises(RuntimeError):
            sut.validate_java_snapshot(state(), snap)

    def test_source_snapshot_table_identity_precise(self):
        snap = {key: snapshot()[key] for key in ("tableId", "directory", "physicalTxn", "sequenceTxn", "schemaHash")}
        snap["table"] = "index_monthly"
        sut.validate_java_snapshot(state(), snap, True)
        snap["table"] = "etf_daily"
        with self.assertRaises(RuntimeError):
            sut.validate_java_snapshot(state(), snap, True)


class ScopedLedgerTests(unittest.TestCase):
    def inspect(self, data, run_ids=None):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as directory:
            path = Path(directory) / "pure.sqlite3"; path.touch()
            db = connection(data)
            with patch.object(sut, "LEDGER", path), patch.object(sut.sqlite3, "connect", return_value=db) as connect:
                result = sut.inspect_ledger(path, ["run"] if run_ids is None else run_ids, snapshot()["targetId"])
                self.assertIn("?mode=ro", connect.call_args.args[0])
                self.assertTrue(connect.call_args.kwargs["uri"])
                db.execute.assert_any_call("PRAGMA query_only=ON")
                db.close.assert_called_once()
            return result

    def test_terminal_real_run_ids_and_zero_leases(self):
        result = self.inspect(ledger_rows(), ["run", "run"])
        self.assertEqual(result["retained_leases"], 0)
        self.assertTrue(result["all_entries_terminal"])

    def test_active_unknown_or_nonterminal_entry_blocks_append(self):
        for wrong in ("IN_DOUBT", "SUBMITTED", "RUNNING", "ACKNOWLEDGED", "CREATED"):
            data = ledger_rows(); data["entries"][-1]["state"] = wrong
            with self.subTest(wrong=wrong), self.assertRaises(RuntimeError):
                self.inspect(data)

    def test_retained_lease_blocks_even_all_verified_entries(self):
        data = ledger_rows(); data["leases"] = [{"id": "lease", "in_doubt": 0}]
        with self.assertRaises(RuntimeError):
            self.inspect(data)

    def test_unrelated_job_missing_run_and_other_target_refused(self):
        for field, wrong in (("job_id", "data.etf_market_overview_daily_cache"), ("target_id", "formal-target"), ("job_version", 2)):
            data = ledger_rows(); data["runs"][0][field] = wrong
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                self.inspect(data)
        with self.assertRaises(RuntimeError):
            self.inspect(ledger_rows(), ["missing"])

    def test_missing_terminal_run_entry_refused(self):
        data = ledger_rows(); data["entries"] = data["entries"][1:]
        with self.assertRaises(RuntimeError):
            self.inspect(data)


class TerminalProtocolTests(unittest.TestCase):
    def protocol(self):
        base = sut.DIRECTORY.resolve()
        path = lambda name: str(base / name)
        pair = lambda name: {"path": path(name), "sha256": "a" * 64}
        args = SimpleNamespace(initial_recovery=Path(path("pure-recovery.json")), initial_recovery_sha256="a" * 64,
            java_initial=Path(path("pure-java.json")), java_initial_sha256="a" * 64,
            terminal_admission=Path(path("pure-terminal.json")), terminal_admission_sha256="a" * 64)
        target = {"pid": 41560, "data_root": str(sut.fixture.ROOT), "http_port": 19030, "pg_port": 18842}
        stable = {"index_monthly": {"exists": True}, "equity_style_monthly": {"exists": False}}
        capture = {"path": path("pure-capture.jsonl"), "sha256": "a" * 64}
        failed = {"whole_real_source": capture, "initial_capture": capture, "held_increment_capture": capture, "private_target_attestation": target,
                  "formal_before": {"mock_formal_snapshot": True}, "preflight_evidence": pair("pure-preflight.json")}
        reconciled = {"task_id": "D103", "status": "VERIFIED_INITIAL_SOURCE_BY_READONLY_RECONCILIATION", "original_insert_ack": "UNKNOWN",
            "source_delivery_exact_verified": True, "java_source_read_admission_recommended": True, "stable_physical_and_wal_versions": True,
            **{name: 0 for name in ("new_source_submissions", "retries", "DDL", "DML", "claim_writes", "old_receipt_writes")},
            "whole_real_source": capture, "initial_capture": capture, "held_increment_capture": capture, "private_target_attestation": target,
            "private_before": stable, "private_after": stable, "formal_before": failed["formal_before"], "formal_after": failed["formal_before"],
            "sender_completion_proof": {"review": pair("pure-sender.json")}}
        materialized = {"result": {"state": "VERIFIED", "verifiedRows": 2}, "targetSnapshotError": None}
        java = {"task_id": "D103", "stage": "initial", "status": "VERIFIED_ISOLATED_INITIAL_REPLAY", "formal_mutated": False,
            "reference_project_mutated": False, "double_tolerance": 0, "key_and_full_field_comparisons": 60, "exact_double_bit_comparisons": 58,
            "fixture_receipt": path("pure-recovery.json"), "fixture_sha256": "a" * 64, "preflight_sha256": "a" * 64,
            "formal_before": {"physical": "stable"}, "formal_after": {"physical": "stable"},
            **{name: copy.deepcopy(materialized) for name in ("first", "same_range_replay", "exact_resume", "readonly_reconcile")},
            "configured_write_group": {"state": "VERIFIED"}, "cancelled_before_source": {"state": "CANCELLED", "verifiedRows": 0},
            "configured_read_group": {"members": [{"memberId": "styles", "datasetId": "equity_style_monthly", "definitionVersion": 1,
                                                 "status": "READ", "page": {"rows": [{}, {}]}}]},
            "source": {"rawRows": 32}, "jvm_pid": 12345, "jvm_birth_utc": "2026-10-06T15:00:00Z", "finished_at": "2026-10-06T15:02:00Z",
            "ledger": str(sut.LEDGER.resolve()), "run_ids": ["first", "replay", "resumed", "reconciled", "typed", "cancelled"],
            "actual_target": {"targetId": snapshot()["targetId"]}}
        gate = {"protocol_version": 1, "task_id": "D103", "decision": "accepted_for_bounded_source_increment", "retry_forbidden": True,
            "initial_recovery": pair("pure-recovery.json"), "java_initial": pair("pure-java.json"), "target": target, "bounded_source": capture,
            "java_process": {"pid": 12345, "birth_utc": java["jvm_birth_utc"], "stopped_observed_at": "2026-10-06T15:03:00Z", "native_evidence": pair("pure-native.json")},
            "executor_completion": pair("pure-executor.json"), "junit": pair("pure-test.xml"),
            "ledger": {"path": str(sut.LEDGER.resolve()), "run_ids": java["run_ids"], "terminal_evidence": pair("pure-ledger.json")}}
        native = {"pid": 12345, "birth_utc": java["jvm_birth_utc"], "observed_at": "2026-10-06T15:03:00Z", "original_identity_present": False, "matches": []}
        actual_ledger = {"runs": [{"id": "first"}], "entries": [{"state": "VERIFIED"}], "leases": []}
        terminal = {"ledger_path": str(sut.LEDGER.resolve()), "run_ids": java["run_ids"], "all_entries_terminal": True, "retained_leases": 0, **copy.deepcopy(actual_ledger)}
        bodies = {path("pure-recovery.json"): reconciled, path("pure-java.json"): java, path("pure-terminal.json"): gate,
                  path("pure-native.json"): native, path("pure-executor.json"): {"exit_code": 0}, path("pure-ledger.json"): terminal}
        return args, failed, java, gate, bodies, actual_ledger

    def consume(self, data):
        args, failed, java, gate, bodies, ledger = data
        normalized = lambda item: {"path": str(Path(item["path"]).resolve()), "sha256": item["sha256"]}
        evidence = lambda path, sha=None: {"path": str(Path(path).resolve()), "sha256": sha}
        sender = {"executor_completion": gate["executor_completion"], "ownership": {"producer_source": gate["executor_completion"]}}
        with patch.object(sut, "JAVA_RECEIPT", args.java_initial), patch.object(sut, "proof_file", side_effect=normalized), \
                patch.object(sut.fixture, "load", side_effect=lambda path, sha=None: bodies[str(Path(path).resolve())]), \
                patch.object(sut.recovery, "failed_inputs", return_value=(failed, {}, [], [], {}, [])), \
                patch.object(sut.recovery, "completion_proof", return_value=sender), patch.object(sut.recovery, "evidence", side_effect=evidence), \
                patch.object(sut, "validate_junit", return_value=gate["junit"]), patch.object(sut, "inspect_ledger", return_value=ledger):
            return sut.admission_inputs(args)

    def test_complete_scoped_protocol_consumes_actual_initial_fields(self):
        data = self.protocol()
        result = self.consume(data)
        self.assertEqual(result[1]["run_ids"], data[2]["run_ids"])

    def test_initial_unknown_may_not_be_upgraded(self):
        data = self.protocol(); data[4][str(data[0].initial_recovery)]["original_insert_ack"] = "ACKNOWLEDGED"
        with self.assertRaises(RuntimeError):
            self.consume(data)

    def test_failed_or_missing_required_actual_java_operation_refused(self):
        for field in ("first", "same_range_replay", "exact_resume", "readonly_reconcile"):
            data = self.protocol(); data[2][field]["result"]["state"] = "IN_DOUBT"
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                self.consume(data)

    def test_manifest_run_ids_and_initial_fixture_sha_bound(self):
        data = self.protocol(); data[3]["ledger"]["run_ids"] = ["different"]
        with self.assertRaises(RuntimeError):
            self.consume(data)
        data = self.protocol(); data[2]["fixture_sha256"] = "b" * 64
        with self.assertRaises(RuntimeError):
            self.consume(data)

    def test_same_original_jvm_present_blocks_terminal_admission(self):
        data = self.protocol()
        data[4][data[3]["java_process"]["native_evidence"]["path"]]["original_identity_present"] = True
        with self.assertRaises(RuntimeError):
            self.consume(data)

    def test_executor_boolean_exit_and_actual_terminal_rows_drift_refused(self):
        data = self.protocol(); data[4][data[3]["executor_completion"]["path"]]["exit_code"] = False
        with self.assertRaises(RuntimeError):
            self.consume(data)
        data = self.protocol(); data[4][data[3]["ledger"]["terminal_evidence"]["path"]]["entries"] = []
        with self.assertRaises(RuntimeError):
            self.consume(data)


class DurableNewOnlyDmlTests(unittest.TestCase):
    def attempt(self, body=b'{"dml":"OK"}', side_effect=None, boundary=None):
        context = tempfile.TemporaryDirectory(dir=sut.DIRECTORY)
        directory = Path(context.name); output, claim = directory / "result.json", directory / "claim.json"
        result = {"invocation_id": "pure-new-increment", "operations": [], "attempted_source_rows": 0, "acknowledged_source_rows": 0, "acknowledged_operations": 0}
        sut.audit.save_new(output, result)
        target = MagicMock(); target.verify.return_value = {"pid": 41560}
        response = MagicMock(); response.status = 200; response.read.return_value = body
        return context, output, claim, result, target, response, side_effect, boundary or MagicMock()

    def test_raw_response_durable_before_ack_recognition(self):
        context, output, claim, result, target, response, _, boundary = self.attempt()
        with context, patch.object(sut, "CLAIM", claim), patch.object(sut, "urlopen", return_value=response) as driver:
            original = sut.validate_dml_ack
            def validate(payload):
                durable = sut.fixture.load(claim)
                self.assertEqual(durable["ack"], "UNKNOWN")
                self.assertEqual(base64.b64decode(durable["http_response"]["body_base64"]), b'{"dml":"OK"}')
                original(payload)
            with patch.object(sut, "validate_dml_ack", side_effect=validate):
                sut.submit_increment_once(target, rows(), claim, output, result, boundary)
            self.assertEqual(driver.call_count, 1)
            self.assertEqual(sut.fixture.load(claim)["ack"], "ACKNOWLEDGED")
            self.assertEqual((result["attempted_source_rows"], result["acknowledged_source_rows"]), (16, 16))
            self.assertEqual(boundary.call_count, 2)
            response.__exit__.assert_called_once()

    def test_timeout_keeps_durable_unknown_and_no_retry(self):
        context, output, claim, result, target, response, _, boundary = self.attempt()
        with context, patch.object(sut, "CLAIM", claim), patch.object(sut, "urlopen", side_effect=TimeoutError("unknown")) as driver:
            with self.assertRaises(TimeoutError):
                sut.submit_increment_once(target, rows(), claim, output, result, boundary)
            durable = sut.fixture.load(claim)
            self.assertEqual(durable["ack"], "UNKNOWN")
            self.assertTrue(durable["attempted"])
            self.assertEqual(result["acknowledged_source_rows"], 0)
            with self.assertRaises(RuntimeError):
                sut.submit_increment_once(target, rows(), claim, output, result, boundary)
            self.assertEqual(driver.call_count, 1)

    def test_wrong_ack_keeps_actual_response_and_unknown(self):
        context, output, claim, result, target, response, _, boundary = self.attempt(body=b'{"ddl":"OK"}')
        with context, patch.object(sut, "CLAIM", claim), patch.object(sut, "urlopen", return_value=response) as driver:
            with self.assertRaises(RuntimeError):
                sut.submit_increment_once(target, rows(), claim, output, result, boundary)
            durable = sut.fixture.load(claim)
            self.assertEqual(durable["ack"], "UNKNOWN")
            self.assertEqual(base64.b64decode(durable["http_response"]["body_base64"]), b'{"ddl":"OK"}')
            self.assertEqual(driver.call_count, 1)
            self.assertEqual(result["acknowledged_source_rows"], 0)

    def test_duplicate_response_key_remains_unknown(self):
        context, output, claim, result, target, response, _, boundary = self.attempt(body=b'{"dml":"OK","dml":"OK"}')
        with context, patch.object(sut, "CLAIM", claim), patch.object(sut, "urlopen", return_value=response):
            with self.assertRaises(RuntimeError):
                sut.submit_increment_once(target, rows(), claim, output, result, boundary)
            self.assertEqual(sut.fixture.load(claim)["ack"], "UNKNOWN")

    def test_success_existing_ack_claim_cannot_be_replayed(self):
        context, output, claim, result, target, response, _, boundary = self.attempt()
        with context, patch.object(sut, "CLAIM", claim), patch.object(sut, "urlopen", return_value=response) as driver:
            sut.submit_increment_once(target, rows(), claim, output, result, boundary)
            with self.assertRaises(RuntimeError):
                sut.submit_increment_once(target, rows(), claim, output, result, boundary)
            self.assertEqual(driver.call_count, 1)

    def test_cancel_and_changed_second_boundary_do_not_call_http(self):
        for cancel, changing in ((True, False), (False, True)):
            context, output, claim, result, target, response, _, boundary = self.attempt()
            if changing:
                boundary.side_effect = [None, RuntimeError("source/ledger/producer changed")]
            with context, patch.object(sut, "CLAIM", claim), patch.object(sut, "urlopen") as driver, \
                    patch.object(sut.audit, "check_cancel", side_effect=RuntimeError("cancel") if cancel else None):
                with self.assertRaises(RuntimeError):
                    sut.submit_increment_once(target, rows(), claim, output, result, boundary)
                driver.assert_not_called()
                self.assertEqual(result["attempted_source_rows"], 0)

    def test_http_non200_response_stored_without_ack(self):
        context, output, claim, result, target, response, _, boundary = self.attempt(body=b'{"error":"not accepted"}')
        response.status = 400
        with context, patch.object(sut, "CLAIM", claim), patch.object(sut, "urlopen", return_value=response):
            with self.assertRaises(RuntimeError):
                sut.submit_increment_once(target, rows(), claim, output, result, boundary)
            durable = sut.fixture.load(claim)
            self.assertEqual(durable["http_response"]["http_status"], 400)
            self.assertEqual(durable["ack"], "UNKNOWN")


if __name__ == "__main__":
    unittest.main()
