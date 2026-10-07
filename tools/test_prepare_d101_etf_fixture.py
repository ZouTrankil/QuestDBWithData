import json
from pathlib import Path
import tempfile
import unittest
import sqlite3
import hashlib
from types import SimpleNamespace
from unittest import mock

import preflight_d101_increment_sources_readonly as preflight
from contextlib import closing

import prepare_d101_etf_fixture as fixture


class FixtureGuards(unittest.TestCase):
    def records(self):
        root = fixture.ROOT.resolve()
        command = f'"D:\\tool\\questdb\\db\\bin\\java.exe" -m io.questdb/io.questdb.ServerMain -d "{root}"'
        return [{"port": port, "address": "127.0.0.1", "pid": 123,
                 "name": "java.exe", "command": command} for port in (19020, 18832)]

    def test_exact_identity(self):
        self.assertEqual(123, fixture.validate_listener_records(self.records(), fixture.ROOT.resolve(), 123)["pid"])

    def test_pid_must_be_positive_integer(self):
        for pid in (True, 0, -1, None, "123"):
            with self.assertRaises(RuntimeError):
                fixture.validate_listener_records(self.records(), fixture.ROOT.resolve(), pid)

    def test_exact_two_ports(self):
        for records in (None, {}, [], self.records()[:1], self.records() * 2):
            with self.assertRaises(RuntimeError):
                fixture.validate_listener_records(records, fixture.ROOT.resolve(), 123)

    def test_foreign_port(self):
        records = self.records()
        records[1]["port"] = 8812
        with self.assertRaises(RuntimeError):
            fixture.validate_listener_records(records, fixture.ROOT.resolve(), 123)

    def test_foreign_pid(self):
        records = self.records()
        records[1]["pid"] = 124
        with self.assertRaises(RuntimeError):
            fixture.validate_listener_records(records, fixture.ROOT.resolve(), 123)

    def test_non_loopback(self):
        records = self.records()
        records[0]["address"] = "0.0.0.0"
        with self.assertRaises(RuntimeError):
            fixture.validate_listener_records(records, fixture.ROOT.resolve(), 123)

    def test_foreign_process(self):
        records = self.records()
        records[0]["name"] = "python.exe"
        with self.assertRaises(RuntimeError):
            fixture.validate_listener_records(records, fixture.ROOT.resolve(), 123)

    def test_foreign_root(self):
        with self.assertRaises(RuntimeError):
            fixture.validate_listener_records(self.records(), fixture.ROOT.resolve().parent, 123)

    def test_missing_duplicate_or_truncated_root_argument(self):
        for command in ('java -m io.questdb/io.questdb.ServerMain', 'java -d', 'java -d a -d b'):
            records = self.records()
            records[0]["command"] = command
            with self.assertRaises(RuntimeError):
                fixture.validate_listener_records(records, fixture.ROOT.resolve(), 123)

    def test_foreign_module(self):
        records = self.records()
        records[0]["command"] = records[0]["command"].replace('io.questdb/io.questdb.ServerMain', 'some.Other')
        with self.assertRaises(RuntimeError):
            fixture.validate_listener_records(records, fixture.ROOT.resolve(), 123)

    def test_new_only_artifact_does_not_overwrite_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'failure.json'
            fixture.save_new(path, {"status": "FAILED"})
            original = path.read_bytes()
            with self.assertRaises(FileExistsError):
                fixture.save_new(path, {"status": "VERIFIED"})
            self.assertEqual(original, path.read_bytes())

    def test_complete_fields_required(self):
        with self.assertRaises(RuntimeError):
            fixture.validate_rows([{"ts_code": "510300.SH"}], ['timestamp', 'ts_code'])

    def test_null_keys_rejected(self):
        with self.assertRaises(RuntimeError):
            fixture.validate_rows([{"timestamp": None, "ts_code": "510300.SH"}], ['timestamp', 'ts_code'])

    def test_duplicate_keys_remain_observable(self):
        row = {"timestamp": "2026-09-17T00:00:00Z", "ts_code": "510300.SH"}
        with self.assertRaises(RuntimeError):
            fixture.validate_rows([row, row.copy()], list(row))

    def test_capture_full_values_and_exact_binary64(self):
        row = {"timestamp": "2026-09-17T00:00:00Z", "ts_code": "510300.SH", "x": -0.0, "nullable": None}
        self.assertEqual([row], fixture.validate_rows([row], list(row), fixture.source_sha([row])))
        changed = dict(row, x=0.0)
        with self.assertRaises(RuntimeError):
            fixture.validate_rows([changed], list(changed), fixture.source_sha([row]))

    def test_progress_retains_unknown_and_ack(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'intent.json'
            fixture.save_new(path, {"ack": "UNKNOWN"})
            self.assertEqual("UNKNOWN", json.loads(path.read_text())["ack"])
            fixture.save_progress(path, {"ack": "ACKNOWLEDGED"})
            self.assertEqual("ACKNOWLEDGED", json.loads(path.read_text())["ack"])

    def ledger(self, directory):
        path = Path(directory) / 'java.sqlite3'
        with closing(sqlite3.connect(path)) as c, c:
            c.execute('CREATE TABLE sync_entries(kind TEXT,state TEXT)')
            c.execute('CREATE TABLE sync_runs(job_id TEXT)')
            c.execute('CREATE TABLE sync_interval_locks(id TEXT)')
            c.execute("INSERT INTO sync_entries VALUES ('RUN','VERIFIED'),('SLICE','VERIFIED')")
            c.execute("INSERT INTO sync_runs VALUES ('data.etf_market_overview_daily_cache')")
        return path

    def test_actual_quiescent_java_ledger(self):
        with tempfile.TemporaryDirectory(dir=fixture.ROOT.parent, prefix='d101-fixture-test-') as temp:
            path = self.ledger(temp)
            self.assertEqual(1, fixture.require_quiescent_java_ledger(path)['runs'])

    def test_unknown_or_active_ledger_blocks_source_changes(self):
        with tempfile.TemporaryDirectory(dir=fixture.ROOT.parent, prefix='d101-fixture-test-') as temp:
            path = self.ledger(temp)
            for state in ('IN_DOUBT', 'SUBMITTED', 'ACKNOWLEDGED', 'RUNNING', 'PENDING', 'FETCHED', 'VALIDATED'):
                with closing(sqlite3.connect(path)) as c, c:
                    c.execute("UPDATE sync_entries SET state=? WHERE kind='SLICE'", (state,))
                with self.assertRaises(RuntimeError):
                    fixture.require_quiescent_java_ledger(path)

    def test_retained_lease_blocks_source_changes(self):
        with tempfile.TemporaryDirectory(dir=fixture.ROOT.parent, prefix='d101-fixture-test-') as temp:
            path = self.ledger(temp)
            with closing(sqlite3.connect(path)) as c, c:
                c.execute("INSERT INTO sync_interval_locks VALUES ('retained')")
            with self.assertRaises(RuntimeError):
                fixture.require_quiescent_java_ledger(path)

    def test_foreign_or_empty_job_ledger_rejected(self):
        with tempfile.TemporaryDirectory(dir=fixture.ROOT.parent, prefix='d101-fixture-test-') as temp:
            path = self.ledger(temp)
            with closing(sqlite3.connect(path)) as c, c:
                c.execute("UPDATE sync_runs SET job_id='data.etf_daily'")
            with self.assertRaises(RuntimeError):
                fixture.require_quiescent_java_ledger(path)
            with closing(sqlite3.connect(path)) as c, c:
                c.execute('DELETE FROM sync_runs')
            with self.assertRaises(RuntimeError):
                fixture.require_quiescent_java_ledger(path)



class IncrementAdmissionGuards(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve(); self.root = self.directory / "private"; self.root.mkdir()
        self.ledger = self.directory / "d101-java-unit.sqlite3"; self.ledger.write_bytes(b"")
        self.first_path, self.hit_path, self.initial_path = [self.directory / name for name in ("first.json", "hit.json", "source-fixture-initial-20261006.json")]
        self.preflight_path, self.admission_path = self.directory / "preflight.json", self.directory / "admission.json"
        self.source_version = "a" * 64; self.target_id = "questdb-" + "b" * 64
        self.identity = {"pid": 23388, "data_root": str(self.root), "host": "127.0.0.1", "http_port": 19020, "pg_port": 18832}
        self.tables = {table: {"actualRows": 2 if table in (fixture.audit.CACHE, fixture.audit.COVERAGE) else 1,
                             "physicalTxn": 2, "id": index + 1} for index, table in enumerate(fixture.TABLES)}
        self.raw = {table: {"physical": {"table_txn": 2}, "wal": {"sequencerTxn": 2}} for table in fixture.TABLES}
        self.ledgers = {str(self.ledger): {"path": str(self.ledger), "runs": 2, "entries": 6, "active_or_unknown": 0, "leases": 0}}
        self.initial = {"status": "VERIFIED_COMPLETE_REAL_SOURCE_FIXTURE", "source_captures": {}}
        self.save(self.initial_path, self.initial)
        def stage(kind):
            return {"task_id": "D101", "stage": kind, "status": "VERIFIED_CANONICAL_FIRST_MISSES" if kind == "first" else "VERIFIED_CANONICAL_HITS_ZERO_PUBLISH",
                    "run": {"id": kind + "-run"}, "ledger_path": str(self.ledger), "source_version_before": self.source_version,
                    "source_version_after": self.source_version, "target_id": self.target_id, "tables_before": self.tables, "tables_after": self.tables,
                    "actual_owner_hits": 0 if kind == "first" else 2, "actual_owner_misses": 2 if kind == "first" else 0,
                    "cache_submitted_rows": 2 if kind == "first" else 0, "coverage_submitted_rows": 2 if kind == "first" else 0,
                    "retained_interval_leases": 0, "source_mutations": 0, "formal_written_rows": 0, "java_independent_cache_or_receipt_writes": 0,
                    "bridge_config": {"privateRoot": str(self.root), "expectedPid": 23388}, "source_fixture_sha256": self.sha(self.initial_path)}
        self.first, self.hit = stage("first"), stage("hit"); self.save(self.first_path, self.first); self.save(self.hit_path, self.hit)
        self.proofs = [{"stage": "first"}, {"stage": "hit"}]
        self.preflight_value = {"task_id": "D101", "status": "VERIFIED_READONLY_INCREMENT_PREFLIGHT", "database_writes": 0, "formal_operations": 0,
                                "owner_invoked": False, "stable_five_table_frontier": True, "source_field_comparisons": 136072,
                                "private_target": self.identity, "ledger_path": str(self.ledger), "target_id": self.target_id,
                                "sources_fingerprint": self.source_version, "tables_before": self.tables, "tables_after": self.tables,
                                "raw_tables_before": self.raw, "raw_tables_after": self.raw, "ledgers_before": self.ledgers,
                                "ledgers_after": self.ledgers, "sources": {}, "producer_evidence": self.proofs}
        self.admission_value = {"task_id": "D101", "protocol_version": 1, "decision": "accepted_for_isolated_source_increment",
                                "java_ledger_path": str(self.ledger), "private_target": self.identity, "target_id": self.target_id,
                                "sources_fingerprint": self.source_version, "increment": fixture.INCREMENT_POLICY.copy(),
                                "protected_tables": [fixture.audit.CACHE, fixture.audit.COVERAGE], "script_sha256": self.sha(Path(fixture.__file__))}
        self.args = SimpleNamespace(java_ledger=self.ledger, preflight=self.preflight_path, preflight_sha256=None,
                                    admission=self.admission_path, admission_sha256=None, expected_pid=23388)
        self.resign()
        for obj, name, value in ((fixture, "DIRECTORY", self.directory), (fixture, "ROOT", self.root),
                                 (preflight, "DIRECTORY", self.directory), (preflight, "INITIAL", self.initial_path)):
            patcher = mock.patch.object(obj, name, value); patcher.start(); self.addCleanup(patcher.stop)
        self.capture_patch = mock.patch.object(fixture, "require_increment_captures"); self.capture_check = self.capture_patch.start(); self.addCleanup(self.capture_patch.stop)
        self.producers = self.start_patch(preflight, "producer_proofs", side_effect=lambda stage, *_: ([{"stage": stage["stage"]}], {901 if stage["stage"] == "first" else 902}))
        self.inventory = self.start_patch(preflight, "ledger_inventory", return_value=self.ledgers)
        self.native = self.start_patch(preflight, "require_producers_absent", return_value={"current_matches": []})
        self.observed = self.start_patch(preflight, "observed_tables", return_value=(self.tables, self.raw))
        self.connection = mock.Mock(); self.connect = self.start_patch(fixture.sqlite3, "connect", return_value=self.connection)
        self.run_state, self.slice_states = "VERIFIED", [("VERIFIED",), ("VERIFIED",)]
        def execute(sql, params=()):
            cursor = mock.Mock()
            if "SELECT job_id,target_id,frozen_json" in sql:
                cursor.fetchone.return_value = ("data.etf_market_overview_daily_cache", self.target_id,
                                              json.dumps({"parameters": {"source_version": self.source_version}}))
            elif "kind='RUN'" in sql:
                cursor.fetchone.return_value = (self.run_state,)
            elif "kind='SLICE'" in sql:
                cursor.fetchall.return_value = self.slice_states
            return cursor
        self.connection.execute.side_effect = execute
        self.target = mock.Mock(); self.target.identity = self.identity

    def start_patch(self, obj, name, **kwargs):
        patcher = mock.patch.object(obj, name, **kwargs); value = patcher.start(); self.addCleanup(patcher.stop); return value

    @staticmethod
    def save(path, value):
        Path(path).write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    @staticmethod
    def sha(path):
        return hashlib.sha256(Path(path).read_bytes()).hexdigest()

    def resign(self):
        inputs = [{"path": str(path), "sha256": self.sha(path)} for path in (self.first_path, self.hit_path, self.initial_path)]
        self.preflight_value["input_evidence"] = inputs
        self.save(self.preflight_path, self.preflight_value); self.args.preflight_sha256 = self.sha(self.preflight_path)
        for field, item in zip(("first", "hit", "initial_source_fixture"), inputs): self.admission_value[field] = item
        self.admission_value["preflight"] = {"path": str(self.preflight_path), "sha256": self.args.preflight_sha256}
        self.save(self.admission_path, self.admission_value); self.args.admission_sha256 = self.sha(self.admission_path)

    def test_exact_bound_first_and_new_complete_hit_admission_is_readonly(self):
        context = fixture.admit_increment(self.args)
        self.assertEqual({901, 902}, context["pids"]); self.capture_check.assert_called_once()
        boundary = fixture.increment_boundary(context, self.target, initial=True)
        self.assertEqual(self.ledgers, boundary["ledgers"]); self.inventory.assert_called_once_with(self.ledger)
        self.native.assert_called_once_with({901, 902}); self.assertEqual(2, self.target.verify.call_count)
        for call in self.connect.call_args_list:
            self.assertIn("?mode=ro", call.args[0]); self.assertTrue(call.kwargs["uri"])

    def test_missing_preflight_or_explicit_admission_fails_before_native_or_database(self):
        for field in ("java_ledger", "preflight", "preflight_sha256", "admission", "admission_sha256"):
            args = SimpleNamespace(**vars(self.args)); setattr(args, field, None)
            with self.assertRaises(RuntimeError): fixture.admit_increment(args)
        self.connect.assert_not_called(); self.native.assert_not_called()

    def test_invalid_or_changed_evidence_sha_fails_before_any_guard_side_effect(self):
        for value in ("bad", "A" * 64, True, "c" * 64):
            args = SimpleNamespace(**vars(self.args)); args.preflight_sha256 = value
            with self.assertRaises(RuntimeError): fixture.admit_increment(args)
        self.connect.assert_not_called(); self.producers.assert_not_called()

    def test_wrong_admission_policy_process_ports_or_frozen_script_is_rejected(self):
        original = json.loads(json.dumps(self.admission_value))
        for mutation in ("decision", "pid", "port", "script", "date", "rows", "tables"):
            self.admission_value = json.loads(json.dumps(original))
            if mutation == "decision": self.admission_value["decision"] = "pending"
            elif mutation == "pid": self.admission_value["private_target"]["pid"] = 123
            elif mutation == "port": self.admission_value["private_target"]["pg_port"] = 8812
            elif mutation == "script": self.admission_value["script_sha256"] = "c" * 64
            elif mutation == "date": self.admission_value["increment"]["trade_dates"] = ["2026-09-22"]
            elif mutation == "rows": self.admission_value["increment"]["max_rows"] = 50000
            else: self.admission_value["increment"]["tables"] = [fixture.audit.CACHE]
            self.resign()
            with self.assertRaises(RuntimeError): fixture.admit_increment(self.args)
        self.connect.assert_not_called()

    def test_failed_nonreadonly_or_unstable_preflight_is_rejected(self):
        original = json.loads(json.dumps(self.preflight_value))
        for field, value in (("status", "FAILED"), ("database_writes", 1), ("formal_operations", True), ("owner_invoked", True), ("stable_five_table_frontier", False), ("source_field_comparisons", 1)):
            self.preflight_value = json.loads(json.dumps(original)); self.preflight_value[field] = value; self.resign()
            with self.assertRaises(RuntimeError): fixture.admit_increment(self.args)
        self.connect.assert_not_called()

    def test_old_failed_hit_cannot_stand_in_for_new_complete_hit(self):
        self.hit["status"] = "FAILED"; self.save(self.hit_path, self.hit); self.resign()
        with self.assertRaises(RuntimeError): fixture.admit_increment(self.args)
        self.producers.assert_not_called()

    def test_artifact_verified_claim_requires_actual_run_and_both_slice_states(self):
        for state, slices in (("PARTIAL", [("VERIFIED",), ("VERIFIED",)]), ("VERIFIED", [("VERIFIED",)]), ("VERIFIED", [("VERIFIED",), ("IN_DOUBT",)])):
            self.run_state, self.slice_states = state, slices
            with self.assertRaises(RuntimeError): fixture.admit_increment(self.args)
        self.producers.assert_not_called()

    def test_initial_five_table_drift_blocks_source_mutation(self):
        context = fixture.admit_increment(self.args); changed = json.loads(json.dumps(self.tables)); changed["etf_share"]["physicalTxn"] += 1
        self.observed.return_value = (changed, self.raw)
        with self.assertRaises(RuntimeError): fixture.increment_boundary(context, self.target, initial=True)

    def test_other_ledger_active_or_any_producer_present_blocks_every_batch(self):
        context = fixture.admit_increment(self.args); self.inventory.side_effect = RuntimeError("Another ledger retains an IN_DOUBT lease")
        with self.assertRaises(RuntimeError): fixture.increment_boundary(context, self.target)
        self.native.assert_not_called(); self.inventory.side_effect = None
        self.native.side_effect = RuntimeError("Original or reused producer PID still present")
        with self.assertRaises(RuntimeError): fixture.increment_boundary(context, self.target)

    def test_admission_sha_and_protected_targets_are_rechecked_before_every_batch(self):
        context = fixture.admit_increment(self.args)
        changed = json.loads(json.dumps(self.tables)); changed[fixture.audit.CACHE]["actualRows"] = 3; self.observed.return_value = (changed, self.raw)
        with self.assertRaises(RuntimeError): fixture.increment_boundary(context, self.target)
        self.observed.return_value = (self.tables, self.raw); self.admission_path.write_text("{}", encoding="utf-8")
        with self.assertRaises(RuntimeError): fixture.increment_boundary(context, self.target)

    def test_only_admitted_source_frontier_may_grow_after_initial_check(self):
        context = fixture.admit_increment(self.args); changed = json.loads(json.dumps(self.tables)); raw = json.loads(json.dumps(self.raw)); changed["etf_share"]["physicalTxn"] += 1; raw["etf_share"]["physical"]["table_txn"] += 1
        self.observed.return_value = (changed, raw)
        fixture.increment_boundary(context, self.target)
        with self.assertRaises(RuntimeError): fixture.increment_boundary(context, self.target, initial=True)

    def test_complete_initial_capture_file_and_full_row_sha_are_bound_to_preflight(self):
        self.capture_patch.stop(); fields = ["timestamp", "ts_code", "x", "nullable"]
        source_models = {table: SimpleNamespace(get_questdb_schema=lambda: {"schema": {field: "STRING" for field in fields}}) for table in fixture.audit.SPEC.sources}
        counts = {table: 1 for table in fixture.audit.SPEC.sources}; sources, initial = {}, {"status": "VERIFIED_COMPLETE_REAL_SOURCE_FIXTURE", "source_captures": {}}
        for table in fixture.audit.SPEC.sources:
            rows = [{"timestamp": "2026-09-17T00:00:00Z", "ts_code": "510300.SH", "x": -0.0, "nullable": None}]
            path = self.directory / f"real-source-{table}-initial-20261006.json"; digest = fixture.source_sha(rows)
            self.save(path, {"table": table, "fields": fields, "rows": rows, "sha256": digest})
            sources[table] = {"fields": fields, "rows": 1, "full_field_sha256": digest, "capture_file_sha256": self.sha(path)}
            initial["source_captures"][table] = {"path": str(path), "rows": 1, "sha256": digest}
        with mock.patch.object(fixture.audit, "SOURCE_MODELS", source_models), mock.patch.object(fixture, "INCREMENT_INITIAL_COUNTS", counts):
            fixture.require_increment_captures(preflight, {"sources": sources}, initial)
            path = Path(initial["source_captures"]["etf_share"]["path"]); altered = json.loads(path.read_text()); altered["rows"][0]["x"] = 0.0; self.save(path, altered)
            with self.assertRaises(RuntimeError): fixture.require_increment_captures(preflight, {"sources": sources}, initial)


if __name__ == '__main__':
    unittest.main()
