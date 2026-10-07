"""D102 pure guards; every database, filesystem journal and native call is mocked."""
from __future__ import annotations

import copy
import importlib
import math
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch
from contextlib import ExitStack

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
sut = importlib.import_module("audit_d102_etf_view")


def rows():
    return [{"trade_date": day + "T00:00:00Z", "etf_count": 1,
             "total_share": 0.125, "total_size_yi": 0.25} for day in sut.DAYS]


def snapshot():
    return {"table": sut.audit.CACHE, "id": 12, "directory": "etf_market_overview_daily_cache~12",
            "physicalTxn": 5, "metadataRows": 5, "actualRows": 5, "sequenceTxn": 5, "writerTxn": 5,
            "pendingRows": 0, "bufferedTxns": 0, "tableSuspended": False, "walSuspended": False}


def good_view():
    return {"view_name": sut.VIEW, "view_status": "valid", "invalidation_reason": None,
            "view_sql": sut.OWNER_SELECT, "view_table_dir_name": sut.VIEW + "~14",
            "view_status_update_time": "2026-10-06T12:00:00Z"}


def reader(isolated=True):
    result = sut.Reader.__new__(sut.Reader)
    result.isolated, result.cancel_file = isolated, None
    result.target = MagicMock(root=sut.ROOT, pid=sut.PID)
    result.connection = MagicMock()
    return result


class QueryGuards(unittest.TestCase):
    def test_original_multiline_and_binds_are_single_select(self):
        sut.select(sut.OWNER_TEMPLATE.format(where="WHERE s.timestamp >= %s AND s.timestamp < %s"))

    def test_nonselect_empty_and_semicolon_refuse(self):
        for sql in ("", " ", sut.DDL, "UPDATE etf_share SET fd_share=0", "SELECT 1;", "SELECT 1; SELECT 2"):
            with self.subTest(sql=sql), self.assertRaises(RuntimeError):
                sut.select(sql)

    def test_formal_ddl_refuses_before_driver(self):
        value = reader(False)
        with self.assertRaises(RuntimeError):
            value.create_view(sut.DDL)
        value.connection.cursor.assert_not_called()

    def test_other_private_statement_refuses_before_driver(self):
        value = reader()
        for sql in ("CREATE VIEW other AS (SELECT 1)", "DROP VIEW " + sut.VIEW, "REFRESH MATERIALIZED VIEW other", "INSERT INTO etf_share VALUES(1)"):
            with self.subTest(sql=sql), self.assertRaises(RuntimeError):
                value.create_view(sql)
        value.connection.cursor.assert_not_called()

    def test_exact_private_pid_root_required(self):
        sut.validate_private_target(sut.ROOT, sut.PID)
        for root, pid in ((sut.ROOT, True), (sut.ROOT, 31760), (sut.REPO, sut.PID)):
            with self.subTest(root=root, pid=pid), self.assertRaises(RuntimeError):
                sut.validate_private_target(root, pid)

    def test_private_constructor_rejects_missing_attestation(self):
        with patch.object(sut.psycopg2, "connect") as connect, self.assertRaises(RuntimeError):
            sut.Reader(True)
        connect.assert_not_called()

    def test_sentinel_truncation_refuses(self):
        value = reader()
        cursor = value.connection.cursor.return_value.__enter__.return_value
        cursor.description = [("n",)]
        cursor.fetchmany.return_value = [(1,)] * 4
        with self.assertRaisesRegex(RuntimeError, "sentinel"):
            value.records("SELECT count() AS n FROM etf_share", cap=3)

    def test_duplicate_selected_columns_refuse(self):
        value = reader()
        cursor = value.connection.cursor.return_value.__enter__.return_value
        cursor.description = [("n",), ("n",)]
        with self.assertRaisesRegex(RuntimeError, "Duplicate"):
            value.records("SELECT count() AS n,count() AS n FROM etf_share")

    def test_invalid_budget_refuses(self):
        value = reader()
        for budget in (True, 0, 50001):
            with self.subTest(budget=budget), self.assertRaises(RuntimeError):
                value.records("SELECT 1", cap=budget)
        value.connection.cursor.assert_not_called()

    def test_cancellation_refuses_before_driver(self):
        value = reader()
        value.cancel_file = Path("cancelled")
        with patch.object(Path, "exists", return_value=True), self.assertRaisesRegex(RuntimeError, "cancelled"):
            value.records("SELECT 1")
        value.connection.cursor.assert_not_called()

    def test_pg_read_and_write_socket_waits_have_finite_deadline(self):
        for state in (sut.psycopg2.extensions.POLL_READ, sut.psycopg2.extensions.POLL_WRITE):
            connection = MagicMock()
            connection.poll.return_value = state
            connection.fileno.return_value = 7
            with self.subTest(state=state), patch.object(sut.time, "monotonic", return_value=0), \
                 patch.object(sut.socket_select, "select", return_value=([], [], [])) as wait, \
                 self.assertRaises(sut.psycopg2.OperationalError):
                sut.wait_pg(connection, 20)
            self.assertEqual(20, wait.call_args.args[3])

    def test_pg_timeout_closes_own_connection_and_restores_callback(self):
        connection = MagicMock()
        prior = lambda value: None
        with patch.object(sut.time, "monotonic", side_effect=[0, 21]), \
             patch.object(sut.psycopg2.extensions, "get_wait_callback", return_value=prior), \
             patch.object(sut.psycopg2.extensions, "set_wait_callback") as setting, \
             self.assertRaises(sut.psycopg2.OperationalError):
            with sut.pg_deadline(connection):
                pass
        connection.close.assert_called_once_with()
        self.assertIs(prior, setting.call_args.args[0])


class ValuesAndMetadata(unittest.TestCase):
    def test_full_four_fields_exact_binary64_pass(self):
        result = sut.compare(rows(), rows())
        self.assertTrue(result["passed"])
        self.assertEqual(12, result["field_comparisons"])
        self.assertEqual(6, result["double_raw_bit_comparisons"])
        self.assertEqual(0, result["double_tolerance"])

    def test_one_ulp_business_difference_is_not_accepted(self):
        altered = rows()
        altered[0]["total_share"] = math.nextafter(0.125, math.inf)
        self.assertFalse(sut.compare(altered, rows())["passed"])

    def test_qwp_one_ulp_serialization_is_only_diagnostic(self):
        altered = rows()
        altered[0]["total_share"] = math.nextafter(0.125, math.inf)
        diagnostic = sut.compare(altered, rows())
        sut.validate_qwp_diagnostic(diagnostic)
        self.assertFalse(diagnostic["passed"])
        self.assertEqual(2, diagnostic["mismatches"][0]["decimal_serialization_ulp_distance_bound"])

    def test_qwp_wrong_null_and_large_number_refuse(self):
        for val in (None, 1.0):
            altered = rows()
            altered[0]["total_share"] = val
            with self.subTest(val=val), self.assertRaises(RuntimeError):
                sut.validate_qwp_diagnostic(sut.compare(altered, rows()))

    def test_null_double_is_preserved(self):
        value = rows()
        value[0]["total_share"] = None
        self.assertTrue(sut.compare(value, value)["passed"])
        self.assertIsNone(sut.raw_bits(value)[0]["total_share"])

    def test_missing_date_and_duplicate_key_refuse(self):
        for value in (rows()[:2], [rows()[0], rows()[0], rows()[2]]):
            with self.assertRaises(RuntimeError):
                sut.keyed(value)

    def test_null_blank_nonmidnight_and_timezone_key_refuse(self):
        for day in (None, "", "2026-09-17", "2026-09-17T12:00:00Z", "2026-09-17T00:00:00+08:00"):
            value = rows()
            value[0]["trade_date"] = day
            with self.subTest(day=day), self.assertRaises((RuntimeError, ValueError)):
                sut.keyed(value)

    def test_required_counts_and_nonfinite_double_refuse(self):
        for field, val in (("etf_count", None), ("etf_count", False), ("etf_count", -1),
                           ("etf_count", 1.0), ("total_share", math.inf), ("total_size_yi", math.nan)):
            value = rows()
            value[0][field] = val
            with self.subTest(field=field, val=val), self.assertRaises(RuntimeError):
                sut.keyed(value)

    def test_signed_zero_bits_are_not_coerced(self):
        a, b = rows(), rows()
        a[0]["total_share"], b[0]["total_share"] = 0.0, -0.0
        self.assertFalse(sut.compare(a, b)["passed"])
        self.assertEqual(-9223372036854775808, sut.raw_bits(b)[0]["total_share"])

    def test_wal_invalid_or_unsettled_refuse(self):
        for field, value in (("pendingRows", 1), ("bufferedTxns", 1), ("writerTxn", 4), ("tableSuspended", True), ("walSuspended", True)):
            state = snapshot()
            state[field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_snapshot(state)

    def test_empty_target_keeps_raw_null_and_count_zero(self):
        state = snapshot()
        state.update(physicalTxn=None, metadataRows=None, actualRows=0, sequenceTxn=0, writerTxn=0)
        sut.validate_snapshot(state)
        self.assertIsNone(state["physicalTxn"])
        self.assertIsNone(state["metadataRows"])
        with self.assertRaises(RuntimeError):
            sut.validate_snapshot(state, source=True)

    def test_boolean_actual_counts_and_missing_raw_metadata_refuse(self):
        state = snapshot()
        state["actualRows"] = False
        with self.assertRaises(RuntimeError):
            sut.validate_snapshot(state)
        state = snapshot()
        state.pop("metadataRows")
        with self.assertRaises(KeyError):
            sut.validate_snapshot(state)

    def test_view_missing_invalid_or_other_sql_refuse(self):
        for value in (None, {**good_view(), "view_status": "invalid"}, {**good_view(), "view_sql": "SELECT * FROM other"}):
            with self.assertRaises(RuntimeError):
                sut.validate_view(value)

    def test_view_identity_and_status_history_missing_or_blank_refuse(self):
        for field in ("view_table_dir_name", "view_status_update_time"):
            for value in (None, "", " "):
                changed = good_view()
                if value is None:
                    changed.pop(field)
                else:
                    changed[field] = value
                with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                    sut.validate_view(changed)

    def test_four_field_schema_wrong_type_or_duplicate_refuses(self):
        for value in ([{"column": k, "type": "INT" if k == "etf_count" else v} for k, v in sut.TYPES.items()],
                      [{"column": "trade_date", "type": "TIMESTAMP"}] * 4):
            with patch.object(sut, "query", return_value=value), self.assertRaises(RuntimeError):
                sut.view_schema(True)


class DdlAndAdmission(unittest.TestCase):
    def test_coordinator_sha_or_serial_gate_refuses(self):
        with patch.object(sut, "evidence_path", return_value=sut.GATE.resolve()), patch.object(sut, "load") as load:
            with self.assertRaises(RuntimeError):
                sut.prerequisite(sut.GATE, "0" * 64)
            load.assert_not_called()
            load.return_value = {"task_id": "D101", "decision": "not_accepted"}
            with self.assertRaises(RuntimeError):
                sut.prerequisite(sut.GATE, sut.GATE_SHA)

    def test_existing_wrong_view_is_never_replaced(self):
        value = reader()
        with patch.object(sut, "load", return_value={"decision": "accepted_for_serial_progress"}), \
             patch.object(Path, "exists", return_value=False), \
             patch.object(sut, "view_state", return_value={**good_view(), "view_sql": "SELECT 1"}), self.assertRaises(RuntimeError):
            sut.ensure_view(value, value.target, {}, Path("unused"), {})
        value.connection.cursor.assert_not_called()

    def test_existing_correct_view_is_read_only(self):
        value, result = reader(), {}
        with patch.object(sut, "load", return_value={"decision": "accepted_for_serial_progress"}), \
             patch.object(Path, "exists", return_value=False), patch.object(sut, "view_state", return_value=good_view()):
            sut.ensure_view(value, value.target, {}, Path("unused"), result)
        self.assertFalse(result["alias_created"])
        value.connection.cursor.assert_not_called()

    def test_prior_unknown_ack_blocks_new_attempt_even_if_view_exists(self):
        value = reader()
        with patch.object(sut, "load", side_effect=[{"decision": "accepted_for_serial_progress"}, {"ack": "UNKNOWN"}]), \
             patch.object(Path, "exists", return_value=True), patch.object(sut, "view_state", return_value=good_view()), self.assertRaisesRegex(RuntimeError, "UNKNOWN"):
            sut.ensure_view(value, value.target, {}, Path("unused"), {})
        value.connection.cursor.assert_not_called()

    def test_prior_ack_missing_view_is_not_resubmitted(self):
        value = reader()
        with patch.object(sut, "load", side_effect=[{"decision": "accepted_for_serial_progress"}, {"ack": "ACKNOWLEDGED"}]), \
             patch.object(Path, "exists", return_value=True), patch.object(sut, "view_state", return_value=None), self.assertRaisesRegex(RuntimeError, "resubmission"):
            sut.ensure_view(value, value.target, {}, Path("unused"), {})
        value.connection.cursor.assert_not_called()

    def test_durable_unknown_before_single_driver_attempt_no_retry(self):
        value, result, events = reader(), {}, []
        value.create_view = MagicMock(side_effect=lambda sql: (events.append("driver"), (_ for _ in ()).throw(RuntimeError("unknown ACK"))))
        context = {"typed": {"tables_after": {"cache": snapshot()}}}
        journal = {"task_id": "D102", "isolated": result}
        def save_claim(path, payload):
            self.assertEqual(sut.DDL_CLAIM, path)
            self.assertEqual("UNKNOWN", payload["ack"])
            self.assertFalse(payload["automatic_retry"])
            events.append("durable UNKNOWN")
        def save_journal(path, payload):
            self.assertIs(journal, payload)
            self.assertEqual("UNKNOWN", payload["isolated"]["alias_submission"]["ack"])
            events.append("journal UNKNOWN")
        with patch.object(sut, "load", return_value={"decision": "accepted_for_serial_progress"}), \
             patch.object(Path, "exists", return_value=False), patch.object(sut, "view_state", return_value=None), \
             patch.object(sut, "table_snapshot", return_value=context["typed"]["tables_after"]), \
             patch.object(sut, "schemas"), patch.object(sut, "source_rows"), patch.object(sut, "quiescence", return_value={}), \
             patch.object(sut, "save_new", side_effect=save_claim), patch.object(sut, "save_progress", side_effect=save_journal), \
             self.assertRaisesRegex(RuntimeError, "unknown ACK"):
            sut.ensure_view(value, value.target, context, Path("output"), result, journal=journal)
        self.assertEqual(["durable UNKNOWN", "journal UNKNOWN", "driver"], events)
        self.assertEqual("UNKNOWN", result["alias_submission"]["ack"])
        value.create_view.assert_called_once_with(sut.DDL)

    def test_missing_view_with_changed_protected_frontier_refuses(self):
        value = reader()
        with patch.object(sut, "load", return_value={"decision": "accepted_for_serial_progress"}), \
             patch.object(Path, "exists", return_value=False), patch.object(sut, "view_state", return_value=None), \
             patch.object(sut, "table_snapshot", return_value={"drift": snapshot()}), self.assertRaisesRegex(RuntimeError, "frontiers"):
            sut.ensure_view(value, value.target, {"typed": {"tables_after": {}}}, Path("unused"), {})
        value.connection.cursor.assert_not_called()

    def test_source_read_failure_stays_failure(self):
        value = MagicMock()
        value.records.side_effect = RuntimeError("source unavailable")
        with self.assertRaisesRegex(RuntimeError, "source unavailable"):
            sut.source_rows(value, True, {"captures": {}})

    def test_actual_private_listener_pid_root_and_ports_refuse_mismatch(self):
        records = [{"port": port, "address": "127.0.0.1", "pid": sut.PID, "name": "java.exe",
                    "command": 'java.exe -d "' + str(sut.ROOT.resolve()) + '" io.questdb/io.questdb.ServerMain'}
                   for port in (19020, 18832)]
        for field, value in (("port", 9000), ("pid", 37904), ("address", "0.0.0.0"),
                             ("command", 'java.exe -d "' + str(sut.REPO) + '" io.questdb/io.questdb.ServerMain')):
            changed = copy.deepcopy(records)
            changed[0][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.fixture.validate_listener_records(changed, sut.ROOT.resolve(), sut.PID)

    def test_source_complete_field_change_refuses_exact_capture(self):
        table = next(iter(sut.audit.SOURCE_MODELS))
        fields = tuple(sut.audit.SOURCE_MODELS[table].get_questdb_schema()["schema"])
        expected = {field: None for field in fields}
        expected.update(timestamp="2026-09-17T00:00:00Z", ts_code="159001.SZ")
        changed = dict(expected)
        changed[next(field for field in fields if field not in ("timestamp", "ts_code"))] = "changed"
        value = MagicMock()
        value.records.return_value = [changed]
        with self.assertRaises(RuntimeError):
            sut.source_rows(value, True, {"captures": {table: [expected]}})

    def test_active_or_unknown_ledger_blocks_before_native_query(self):
        actual = sut.REPO / "var/d101-java-mock.sqlite3"
        with patch.object(Path, "glob", return_value=[actual]), patch.object(sut, "evidence_path", return_value=actual), \
             patch.object(sut.fixture, "require_quiescent_java_ledger", side_effect=RuntimeError("active sender")), \
             patch.object(sut.subprocess, "run") as native, self.assertRaisesRegex(RuntimeError, "active sender"):
            sut.quiescence({"typed": {"ledger_path": str(actual)}})
        native.assert_not_called()

    def test_present_or_reused_producer_pid_blocks(self):
        actual = sut.REPO / "var/d101-java-mock.sqlite3"
        stopped = sut.D101 / "java-owner-bridge/example.process-stopped.json"
        with patch.object(Path, "glob", side_effect=[[actual], [stopped]]), \
             patch.object(sut, "evidence_path", return_value=actual), \
             patch.object(sut.fixture, "require_quiescent_java_ledger", return_value={}), \
             patch.object(sut, "load", return_value={"pid": 1234}), patch.object(sut, "digest", return_value="0"*64), \
             patch.object(sut.subprocess, "run", return_value=MagicMock(returncode=0, stdout='[{"ProcessId":1234}]')), \
             self.assertRaisesRegex(RuntimeError, "producer"):
            sut.quiescence({"typed": {"ledger_path": str(actual)}})


class ExplicitProcessIdentityReview(unittest.TestCase):
    def setUp(self):
        self.review_path = sut.DIRECTORY / "explicit-review.json"
        self.binding = {"path": str(self.review_path), "sha256": "a" * 64}
        self.start = sut.D101 / "java-owner-bridge/mock.process-started.json"
        self.stop = sut.D101 / "java-owner-bridge/mock.process-stopped.json"
        self.side = sut.D101 / "java-owner-bridge/mock.process-observed-0.json"
        self.request = sut.D101 / "java-owner-bridge/mock.request.json"
        self.response = sut.D101 / "java-owner-bridge/mock.response.json"
        self.failed = sut.DIRECTORY / "view-isolated-acceptance-20261006.json"
        self.diagnostic = sut.DIRECTORY / "failed-precreate-process-diagnostic-20261006.json"
        self.birth, self.stop_time, self.current_birth = "2026-10-06T10:00:00.001Z", "2026-10-06T10:00:01.001000001Z", "2026-10-06T11:00:00.0010000Z"
        self.identity = {"pid": 1234, "parent_pid": 999, "name": "unrelated.exe", "birth": self.current_birth, "executable": None}
        self.manifest = {str(self.start): "1" * 64, str(self.stop): "2" * 64, str(self.side): "3" * 64}
        root = {"pid": 100, "process_start": "2026-10-06T10:00:00Z", "invocation_id": "invocation", "request_sha256": "4" * 64}
        child = {"pid": 1234, "process_start": self.birth, "parent_pid": 100, "exit_observed": True,
                 "evidence_path": str(self.side), "evidence_sha256": "3" * 64}
        original = {"pid": 1234, "original_birth": self.birth, "original_stopped_at": self.stop_time,
                    "current_birth": self.current_birth, "current_birth_strictly_later": True, "complete_stop_verified": True,
                    "invocation_id": "invocation", "role": "child", "actual_bridge_pid": 1234,
                    "started_path": str(self.start), "started_sha256": "1" * 64,
                    "complete_stop_path": str(self.stop), "complete_stop_sha256": "2" * 64,
                    "observed_path": str(self.side), "observed_sha256": "3" * 64,
                    "request_evidence": {"path": str(self.request), "sha256": "4" * 64},
                    "response_evidence": {"path": str(self.response), "sha256": "5" * 64}}
        self.review = {"protocol_version": 1, "task_id": "D102", "status": "VERIFIED_INDEPENDENT_REUSED_PID_IDENTITIES",
                       "decision": "accepted_for_new_precreate_attempt_only", "blockers": [],
                       "gate_path": str(sut.GATE), "gate_sha256": sut.GATE_SHA,
                       "private_target": {"pid": sut.PID, "data_root": str(sut.ROOT.resolve()), "host": "127.0.0.1", "http_port": 19020, "pg_port": 18832},
                       "failed_evidence_path": str(self.failed), "failed_evidence_sha256": "6" * 64,
                       "diagnostic_path": str(self.diagnostic), "diagnostic_sha256": "7" * 64,
                       "historical_process_files": self.manifest, "approved_native_matches": [self.identity], "original_identities": [original]}
        self.bodies = {str(self.review_path): self.review, str(sut.GATE): {"decision": "accepted_for_serial_progress"},
                       str(self.start): root, str(self.stop): {**root, "process_tree_version": 1, "observed_at": self.stop_time,
                            "exit_observed": True, "process_tree_stopped": True, "tree_observation_complete": True,
                            "response_present": True, "bridge_identity_proved": True, "actual_bridge_pid": 1234, "observed_children": [child]},
                       str(self.side): {**root, "observed_descendant": True, "child_pid": 1234, "child_process_start": self.birth, "parent_pid": 100},
                       str(self.request): {"invocation_id": "invocation"},
                       str(self.response): {"invocation_id": "invocation", "bridge_process": {"pid": 1234}},
                       str(self.failed): {"status": "FAILED", "owner_invoked": False, "formal_mutated": False,
                            "source_written_rows": 0, "cache_written_rows": 0, "coverage_written_rows": 0},
                       str(self.diagnostic): {"status": "READONLY_PRECREATE_DIAGNOSTIC", "failed_evidence_sha256": "6" * 64,
                            "tables_equal_accepted_typed": True, "private_view": None, "create_claim_exists": False,
                            "ddl_attempts": 0, "owner_invocations": 0, "historical_process_files": self.manifest,
                            "native_matches": [self.identity]}}
        self.hashes = {**self.manifest, str(self.review_path): "a" * 64, str(sut.GATE): sut.GATE_SHA,
                       str(self.request): "4" * 64, str(self.response): "5" * 64, str(self.failed): "6" * 64, str(self.diagnostic): "7" * 64}

    def mocks(self):
        def read(path, expected=None):
            if expected is not None:
                sut.require(self.hashes[str(path)] == expected, "Immutable evidence SHA differs")
            return copy.deepcopy(self.bodies[str(path)])
        stack = ExitStack()
        stack.enter_context(patch.object(sut, "evidence_path", side_effect=lambda value: Path(value)))
        stack.enter_context(patch.object(sut, "load", side_effect=read))
        stack.enter_context(patch.object(sut, "digest", side_effect=lambda value: self.hashes[str(value)]))
        stack.enter_context(patch.object(Path, "glob", return_value=[Path(value) for value in self.manifest]))
        return stack

    def test_optional_path_and_sha_are_a_required_pair_before_evidence_read(self):
        with patch.object(sut, "load") as read:
            for path, sha in ((self.review_path, None), (None, "a" * 64)):
                with self.subTest(path=path), self.assertRaisesRegex(RuntimeError, "together"):
                    sut.process_review_binding(path, sha)
            self.assertIsNone(sut.process_review_binding())
        read.assert_not_called()

    def test_explicit_review_sha_must_be_lowercase_sha256(self):
        for sha in ("bad", "A" * 64, True):
            with self.subTest(sha=sha), self.assertRaises(RuntimeError):
                sut.process_review_binding(self.review_path, sha)

    def test_complete_known_child_stop_and_explicit_later_identity_pass(self):
        with self.mocks():
            self.assertEqual({1234: self.identity}, sut.validate_process_review(self.binding, self.manifest))
            self.assertEqual(self.binding, sut.process_review_binding(self.review_path, "a" * 64))

    def test_unaccepted_review_protocol_or_scope_refuses(self):
        for field, value in (("protocol_version", True), ("protocol_version", 2), ("task_id", "D103"),
                             ("status", "UNKNOWN"), ("decision", "accepted_for_serial_progress"), ("blockers", ["unknown"])):
            prior = self.review[field]
            self.review[field] = value
            with self.subTest(field=field), self.mocks(), self.assertRaises(RuntimeError):
                sut.validate_process_review(self.binding)
            self.review[field] = prior

    def test_review_changed_sha_refuses(self):
        self.hashes[str(self.review_path)] = "b" * 64
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "SHA"):
            sut.validate_process_review(self.binding)

    def test_full_historical_manifest_hash_or_inventory_drift_refuses(self):
        self.hashes[str(self.side)] = "b" * 64
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "inventory or SHA"):
            sut.validate_process_review(self.binding)
        self.hashes[str(self.side)] = "3" * 64
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "inventory or SHA"):
            sut.validate_process_review(self.binding, {**self.manifest, "extra": "0" * 64})

    def test_review_wrong_private_target_or_gate_refuses(self):
        self.review["private_target"]["pid"] = 1
        with self.mocks(), self.assertRaises(RuntimeError):
            sut.validate_process_review(self.binding)
        self.review["private_target"]["pid"] = sut.PID
        self.review["gate_sha256"] = "0" * 64
        with self.mocks(), self.assertRaises(RuntimeError):
            sut.validate_process_review(self.binding)

    def test_old_failure_with_submission_or_diagnostic_claim_refuses(self):
        self.bodies[str(self.failed)]["isolated"] = {"alias_submission": {"ack": "UNKNOWN"}}
        with self.mocks(), self.assertRaises(RuntimeError):
            sut.validate_process_review(self.binding)
        self.bodies[str(self.failed)].pop("isolated")
        self.bodies[str(self.diagnostic)]["create_claim_exists"] = True
        with self.mocks(), self.assertRaises(RuntimeError):
            sut.validate_process_review(self.binding)

    def test_current_birth_not_strictly_after_original_stop_refuses(self):
        self.identity["birth"] = self.stop_time
        self.review["original_identities"][0]["current_birth"] = self.stop_time
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "strictly later"):
            sut.validate_process_review(self.binding)

    def test_complete_stop_flags_false_or_missing_refuse(self):
        stop = self.bodies[str(self.stop)]
        for flag in ("exit_observed", "process_tree_stopped", "tree_observation_complete", "response_present", "bridge_identity_proved"):
            stop[flag] = False
            with self.subTest(flag=flag), self.mocks(), self.assertRaisesRegex(RuntimeError, "complete STOP"):
                sut.validate_process_review(self.binding)
            stop[flag] = True

    def test_child_sidecar_birth_or_parent_drift_refuses(self):
        side = self.bodies[str(self.side)]
        for field, value in (("child_process_start", None), ("parent_pid", 999), ("observed_descendant", False)):
            old = side[field]
            side[field] = value
            with self.subTest(field=field), self.mocks(), self.assertRaisesRegex(RuntimeError, "sidecar"):
                sut.validate_process_review(self.binding)
            side[field] = old

    def test_unknown_start_or_unreviewed_original_birth_refuses(self):
        original = self.review["original_identities"][0]
        for value in (None, "2026-10-06T09:00:00Z"):
            old = original["original_birth"]
            original["original_birth"] = value
            with self.subTest(value=value), self.mocks(), self.assertRaises(RuntimeError):
                sut.validate_process_review(self.binding)
            original["original_birth"] = old

    def test_original_request_or_actual_bridge_response_drift_refuses(self):
        self.bodies[str(self.response)]["bridge_process"]["pid"] = 999
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "actual bridge"):
            sut.validate_process_review(self.binding)
        self.bodies[str(self.response)]["bridge_process"]["pid"] = 1234
        self.hashes[str(self.request)] = "0" * 64
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "SHA"):
            sut.validate_process_review(self.binding)

    def test_default_present_or_reused_pid_still_refuses(self):
        with self.assertRaisesRegex(RuntimeError, "default PID reuse"):
            sut.approve_native_matches([self.identity])
        sut.approve_native_matches([])

    def test_explicit_review_allows_only_exact_native_birth_name_and_parent(self):
        approved = {1234: self.identity}
        sut.approve_native_matches([self.identity], approved)
        sut.approve_native_matches([], approved)
        for field, value in (("birth", "2026-10-06T12:00:00Z"), ("name", "other.exe"),
                             ("parent_pid", 998), ("pid", 5678), ("pid", sut.PID), ("executable", "other.exe")):
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, "exact explicitly reviewed"):
                sut.approve_native_matches([{**self.identity, field: value}], approved)

    def test_native_query_preserves_utc_os_birth_and_excludes_command_lines(self):
        import json
        with patch.object(sut.subprocess, "run", return_value=MagicMock(returncode=0, stdout=json.dumps([self.identity]))) as call:
            self.assertEqual([self.identity], sut.fresh_native_matches({1234}))
        command = call.call_args.args[0][-1]
        self.assertIn("CreationDate.ToUniversalTime().ToString('o')", command)
        self.assertNotIn("CommandLine", command)
        self.assertEqual(30, call.call_args.kwargs["timeout"])

    def test_native_unknown_birth_boolean_pid_or_server_pid_refuse(self):
        for field, value in (("birth", None), ("birth", "2026-10-06T11:00:00+08:00"), ("pid", True),
                             ("pid", sut.PID), ("parent_pid", None), ("name", "")):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.native_identity({**self.identity, field: value})

    def test_native_query_failure_or_unrequested_pid_refuses(self):
        import json
        for code, body in ((1, "[]"), (0, json.dumps([{**self.identity, "pid": 5678}]))):
            with self.subTest(code=code), patch.object(sut.subprocess, "run", return_value=MagicMock(returncode=code, stdout=body)), self.assertRaises(RuntimeError):
                sut.fresh_native_matches({1234})

    def test_nanosecond_stop_order_does_not_truncate_to_microseconds(self):
        self.assertLess(sut.utc_identity_time("2026-10-06T10:00:00.000000001Z"),
                        sut.utc_identity_time("2026-10-06T10:00:00.000000002Z"))

    def test_every_quiescence_boundary_reloads_review_with_full_manifest(self):
        actual = sut.REPO / "var/d101-java-mock.sqlite3"
        with patch.object(Path, "glob", side_effect=[[actual], [self.stop], [actual], [self.stop]]), \
             patch.object(sut, "evidence_path", return_value=actual), \
             patch.object(sut.fixture, "require_quiescent_java_ledger", return_value={}), \
             patch.object(sut, "load", return_value={"pid": 1234}), patch.object(sut, "digest", return_value="2" * 64), \
             patch.object(sut, "validate_process_review", side_effect=[{1234: self.identity}, RuntimeError("review SHA changed")]) as validate, \
             patch.object(sut, "fresh_native_matches", return_value=[self.identity]) as native:
            context = {"typed": {"ledger_path": str(actual)}, "process_identity_review": self.binding}
            sut.quiescence(context)
            with self.assertRaisesRegex(RuntimeError, "review SHA changed"):
                sut.quiescence(context)
        self.assertEqual(2, validate.call_count)
        self.assertEqual({str(self.stop): "2" * 64}, validate.call_args.args[1])
        native.assert_called_once()


    def completed_review(self):
        self.review.update(protocol_version=2, status="VERIFIED_INDEPENDENT_COMPLETED_PRODUCER_IDENTITIES",
                           decision="accepted_for_fresh_birth_after_complete_stop_only",
                           eligible_pids=[1234], hard_deny_pids=[100], historical_producer_pids=[100, 1234],
                           typed_evidence_path=str(sut.TYPED), typed_evidence_sha256="8" * 64,
                           historical_response_evidence=[{"path": str(self.response), "sha256": "5" * 64,
                               "invocation_id": "invocation", "actual_bridge_pid": 1234}])
        self.bodies[str(sut.TYPED)] = {"status": "VERIFIED_TYPED_OWNER_ASSERTIONS_ZERO_PUBLISH", "retained_interval_leases": 0,
                                       "tables_before": {"cache": snapshot()}, "tables_after": {"cache": snapshot()}}
        self.hashes[str(sut.TYPED)] = "8" * 64
        for field in ("current_birth", "current_birth_strictly_later"):
            self.review["original_identities"][0].pop(field)
        for field, old, name in (("failed_evidence_path", self.failed, "view-isolated-acceptance-new-identity-20261006.json"),
                                 ("diagnostic_path", self.diagnostic, "failed-precreate-identity-delta-diagnostic-20261006.json")):
            path = sut.DIRECTORY / name
            self.bodies[str(path)] = self.bodies.pop(str(old))
            self.hashes[str(path)] = self.hashes.pop(str(old))
            self.review[field] = str(path)
        self.failed = Path(self.review["failed_evidence_path"])
        self.diagnostic = Path(self.review["diagnostic_path"])

    def test_protocol2_complete_history_returns_exhaustive_stop_policy(self):
        self.completed_review()
        with self.mocks():
            self.assertEqual({"protocol_version": 2, "eligible_stop_times": {1234: self.stop_time},
                              "hard_deny_pids": [100], "historical_producer_pids": [100, 1234]},
                             sut.validate_process_review(self.binding, self.manifest))

    def test_protocol2_current_helper_name_parent_and_newer_birth_are_diagnostic_only(self):
        self.completed_review()
        with self.mocks():
            policy = sut.validate_process_review(self.binding)
        current = {**self.identity, "parent_pid": 700, "name": "audit-helper.exe", "executable": "different.exe", "birth": "2026-10-06T12:00:00Z"}
        sut.approve_native_matches([current], policy)
        with self.assertRaises(RuntimeError):
            sut.approve_native_matches([current], {1234: self.identity})  # protocol1 remains exact.

    def test_protocol2_current_birth_equal_or_earlier_than_any_old_stop_refuses(self):
        policy = {"protocol_version": 2, "eligible_stop_times": {1234: self.stop_time}, "hard_deny_pids": [100]}
        for birth in (self.birth, self.stop_time, "2026-10-06T10:00:01.001000000Z"):
            with self.subTest(birth=birth), self.assertRaisesRegex(RuntimeError, "strictly later"):
                sut.approve_native_matches([{**self.identity, "birth": birth}], policy)
        sut.approve_native_matches([{**self.identity, "birth": "2026-10-06T10:00:01.001000002Z"}], policy)

    def test_protocol2_present_hard_denied_or_unlisted_pid_refuses_but_absent_is_safe(self):
        policy = {"protocol_version": 2, "eligible_stop_times": {1234: self.stop_time}, "hard_deny_pids": [100]}
        for pid in (100, 15144, 5678):
            with self.subTest(pid=pid), self.assertRaises(RuntimeError):
                sut.approve_native_matches([{**self.identity, "pid": pid}], policy)
        sut.approve_native_matches([], policy)

    def test_protocol2_unknown_native_birth_or_reserved_server_pid_refuses(self):
        policy = {"protocol_version": 2, "eligible_stop_times": {1234: self.stop_time}, "hard_deny_pids": []}
        for field, value in (("birth", None), ("birth", "UNKNOWN"), ("pid", sut.PID)):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.approve_native_matches([{**self.identity, field: value}], policy)

    def test_protocol2_partition_missing_overlapping_boolean_or_duplicate_pid_refuses(self):
        self.completed_review()
        for field, value in (("hard_deny_pids", []), ("hard_deny_pids", [100, 1234]),
                             ("eligible_pids", [True, 1234]), ("eligible_pids", [1234, 1234]),
                             ("historical_producer_pids", [100, 1234, 4567])):
            old = self.review[field]
            self.review[field] = value
            with self.subTest(field=field, value=value), self.mocks(), self.assertRaises(RuntimeError):
                sut.validate_process_review(self.binding)
            self.review[field] = old

    def test_protocol2_second_failed_and_diagnostic_paths_cannot_use_old_admission(self):
        self.completed_review()
        self.review["failed_evidence_path"] = str(sut.DIRECTORY / "view-isolated-acceptance-20261006.json")
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "paths differ"):
            sut.validate_process_review(self.binding)

    def test_protocol2_every_started_invocation_needs_its_frozen_response(self):
        self.completed_review()
        old = self.review["historical_response_evidence"]
        for value in ([], [old[0], old[0]], [{**old[0], "invocation_id": "other"}], [{**old[0], "path": str(self.request)}]):
            self.review["historical_response_evidence"] = value
            with self.subTest(value=value), self.mocks(), self.assertRaises(RuntimeError):
                sut.validate_process_review(self.binding)

    def test_protocol2_response_sha_or_actual_bridge_pid_drift_refuses(self):
        self.completed_review()
        self.hashes[str(self.response)] = "0" * 64
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "SHA"):
            sut.validate_process_review(self.binding)
        self.hashes[str(self.response)] = "5" * 64
        self.review["historical_response_evidence"][0]["actual_bridge_pid"] = 4567
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "bridge PID"):
            sut.validate_process_review(self.binding)

    def test_protocol2_incomplete_invocation_denies_every_pid_even_with_known_birth(self):
        self.completed_review()
        self.bodies[str(self.stop)]["process_tree_stopped"] = False
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "must remain hard denied"):
            sut.validate_process_review(self.binding)

    def test_protocol2_unreviewed_second_original_instance_refuses(self):
        self.completed_review()
        other = sut.D101 / "java-owner-bridge/mock.process-observed-1.json"
        self.manifest[str(other)] = self.hashes[str(other)] = "9" * 64
        self.bodies[str(other)] = {"pid": 100, "process_start": "2026-10-06T10:00:00Z", "invocation_id": "invocation",
                                  "child_pid": 1234, "child_process_start": "2026-10-06T09:00:00Z"}
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "Unreviewed original birth"):
            sut.validate_process_review(self.binding)

    def test_protocol2_typed_frontier_sha_or_lease_drift_refuses(self):
        self.completed_review()
        self.bodies[str(sut.TYPED)]["retained_interval_leases"] = 1
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "typed frontier"):
            sut.validate_process_review(self.binding)
        self.bodies[str(sut.TYPED)]["retained_interval_leases"] = 0
        self.hashes[str(sut.TYPED)] = "9" * 64
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "SHA"):
            sut.validate_process_review(self.binding)

    def test_protocol2_quiescence_queries_all_history_including_response_only_unknown_pid(self):
        actual = sut.REPO / "var/d101-java-mock.sqlite3"
        policy = {"protocol_version": 2, "eligible_stop_times": {1234: self.stop_time},
                  "hard_deny_pids": [100, 15144], "historical_producer_pids": [100, 1234, 15144]}
        with patch.object(Path, "glob", side_effect=[[actual], [self.stop]]), patch.object(sut, "evidence_path", return_value=actual), \
             patch.object(sut.fixture, "require_quiescent_java_ledger", return_value={}), patch.object(sut, "load", return_value={"pid": 1234}), \
             patch.object(sut, "digest", return_value="2" * 64), patch.object(sut, "validate_process_review", return_value=policy), \
             patch.object(sut, "fresh_native_matches", return_value=[self.identity]) as native:
            result = sut.quiescence({"typed": {"ledger_path": str(actual)}, "process_identity_review": self.binding})
        native.assert_called_once_with({100, 1234, 15144})
        self.assertEqual("fresh_birth_after_complete_stop", result["process_identity_policy"])
        self.assertEqual([100, 1234, 15144], result["checked_producer_pids"])

    def test_protocol2_two_valid_boundaries_allow_current_native_helper_identity_changes(self):
        before = {"process_identity_policy": "fresh_birth_after_complete_stop", "ledgers": {"ledger": "stable"},
                  "process_file_hashes": self.manifest, "checked_producer_pids": [100, 1234],
                  "process_identity_review": self.binding, "native_current_matches": [self.identity]}
        after = {**before, "native_current_matches": [{**self.identity, "birth": "2026-10-06T12:00:00Z", "name": "new-audit-helper.exe"}]}
        self.assertTrue(sut.same_quiescence(before, after))
        for field in ("ledgers", "process_file_hashes", "checked_producer_pids", "process_identity_review", "process_identity_policy"):
            with self.subTest(field=field):
                self.assertFalse(sut.same_quiescence(before, {**after, field: "changed"}))
        strict_before = {**before, "process_identity_policy": "exact_current_identity_or_absent"}
        strict_after = {**after, "process_identity_policy": "exact_current_identity_or_absent"}
        self.assertFalse(sut.same_quiescence(strict_before, strict_after))


    def test_protocol2_all_instances_are_verified_and_latest_stop_controls_reuse(self):
        self.completed_review()
        start = sut.D101 / "java-owner-bridge/mock2.process-started.json"
        stop = sut.D101 / "java-owner-bridge/mock2.process-stopped.json"
        side = sut.D101 / "java-owner-bridge/mock2.process-observed-0.json"
        request = sut.D101 / "java-owner-bridge/mock2.request.json"
        response = sut.D101 / "java-owner-bridge/mock2.response.json"
        birth, stopped_at = "2026-10-06T10:02:00.001Z", "2026-10-06T10:03:00.000000001Z"
        header = {"pid": 200, "process_start": "2026-10-06T10:02:00Z", "invocation_id": "invocation2", "request_sha256": "d" * 64}
        self.manifest.update({str(start): "a" * 64, str(stop): "b" * 64, str(side): "c" * 64})
        self.hashes.update({**self.manifest, str(request): "d" * 64, str(response): "e" * 64})
        self.bodies.update({str(start): header,
            str(stop): {**header, "process_tree_version": 1, "observed_at": stopped_at, "exit_observed": True,
                "process_tree_stopped": True, "tree_observation_complete": True, "response_present": True,
                "bridge_identity_proved": True, "actual_bridge_pid": 1234,
                "observed_children": [{"pid": 1234, "process_start": birth, "parent_pid": 200, "exit_observed": True,
                    "evidence_path": str(side), "evidence_sha256": "c" * 64}]},
            str(side): {**header, "observed_descendant": True, "child_pid": 1234, "child_process_start": birth, "parent_pid": 200},
            str(request): {"invocation_id": "invocation2"}, str(response): {"invocation_id": "invocation2", "bridge_process": {"pid": 1234}}})
        original = {**self.review["original_identities"][0], "original_birth": birth, "original_stopped_at": stopped_at,
                    "invocation_id": "invocation2", "started_path": str(start), "started_sha256": "a" * 64,
                    "complete_stop_path": str(stop), "complete_stop_sha256": "b" * 64,
                    "observed_path": str(side), "observed_sha256": "c" * 64,
                    "request_evidence": {"path": str(request), "sha256": "d" * 64},
                    "response_evidence": {"path": str(response), "sha256": "e" * 64}}
        self.review["original_identities"].append(original)
        self.review["hard_deny_pids"] = [100, 200]
        self.review["historical_producer_pids"] = [100, 200, 1234]
        self.review["historical_response_evidence"].append({"path": str(response), "sha256": "e" * 64,
            "invocation_id": "invocation2", "actual_bridge_pid": 1234})
        with self.mocks():
            policy = sut.validate_process_review(self.binding)
        self.assertEqual(stopped_at, policy["eligible_stop_times"][1234])
        with self.assertRaises(RuntimeError):
            sut.approve_native_matches([{**self.identity, "birth": "2026-10-06T10:02:30Z"}], policy)
        sut.approve_native_matches([{**self.identity, "birth": "2026-10-06T10:03:00.000000002Z"}], policy)


    def add_reused_leaf(self):
        self.completed_review()
        self.leaf_sides = []
        for index, birth, sha in ((1, "2026-10-06T10:00:00.002Z", "9" * 64),
                                  (2, "2026-10-06T10:00:00.003Z", "f" * 64)):
            side = sut.D101 / f"java-owner-bridge/mock.process-observed-{index}.json"
            self.leaf_sides.append(side)
            self.manifest[str(side)] = self.hashes[str(side)] = sha
            self.bodies[str(side)] = {**self.bodies[str(self.start)], "observed_descendant": True,
                                      "child_pid": 12620, "child_process_start": birth, "parent_pid": 1234}
            self.bodies[str(self.stop)]["observed_children"].append({"pid": 12620, "process_start": birth,
                "parent_pid": 1234, "exit_observed": True, "evidence_path": str(side), "evidence_sha256": sha})
            self.review["original_identities"].append({**self.review["original_identities"][0], "pid": 12620,
                "original_birth": birth, "observed_path": str(side), "observed_sha256": sha})
        self.review["eligible_pids"] = [1234, 12620]
        self.review["historical_producer_pids"] = [100, 1234, 12620]

    def test_protocol2_distinct_known_births_of_same_leaf_pid_pass(self):
        self.add_reused_leaf()
        with self.mocks():
            policy = sut.validate_process_review(self.binding)
        self.assertEqual({1234: self.stop_time, 12620: self.stop_time}, policy["eligible_stop_times"])

    def test_protocol2_exact_duplicate_leaf_identity_refuses(self):
        self.add_reused_leaf()
        self.bodies[str(self.stop)]["observed_children"][2]["process_start"] = "2026-10-06T10:00:00.002Z"
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "Duplicate original child OS identity"):
            sut.validate_process_review(self.binding)

    def test_protocol2_equivalent_iso_precision_is_the_same_os_identity(self):
        self.add_reused_leaf()
        self.bodies[str(self.stop)]["observed_children"][2]["process_start"] = "2026-10-06T10:00:00.002000000Z"
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "Duplicate original child OS identity"):
            sut.validate_process_review(self.binding)

    def test_protocol2_reused_parent_pid_is_ambiguous_and_refuses(self):
        self.add_reused_leaf()
        self.bodies[str(self.stop)]["observed_children"][0]["parent_pid"] = 12620
        self.bodies[str(self.side)]["parent_pid"] = 12620
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "parent PID has ambiguous OS births"):
            sut.validate_process_review(self.binding)

    def test_protocol2_reused_actual_bridge_pid_is_ambiguous_and_refuses(self):
        self.add_reused_leaf()
        self.bodies[str(self.stop)]["actual_bridge_pid"] = 12620
        self.bodies[str(self.response)]["bridge_process"]["pid"] = 12620
        self.review["historical_response_evidence"][0]["actual_bridge_pid"] = 12620
        for original in self.review["original_identities"]:
            original["actual_bridge_pid"] = 12620
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "actual bridge PID is unknown or has ambiguous"):
            sut.validate_process_review(self.binding)

    def test_protocol2_root_pid_cannot_be_a_reused_child_identity(self):
        self.add_reused_leaf()
        self.bodies[str(self.stop)]["observed_children"][1]["pid"] = 100
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "child identity/exit"):
            sut.validate_process_review(self.binding)

    def test_protocol1_retains_pid_unique_children_even_when_birth_differs(self):
        side = sut.D101 / "java-owner-bridge/mock.process-observed-1.json"
        self.manifest[str(side)] = self.hashes[str(side)] = "9" * 64
        birth = "2026-10-06T10:00:00.002Z"
        self.bodies[str(side)] = {**self.bodies[str(self.start)], "observed_descendant": True,
                                  "child_pid": 1234, "child_process_start": birth, "parent_pid": 100}
        self.bodies[str(self.stop)]["observed_children"].append({"pid": 1234, "process_start": birth,
            "parent_pid": 100, "exit_observed": True, "evidence_path": str(side), "evidence_sha256": "9" * 64})
        with self.mocks(), self.assertRaisesRegex(RuntimeError, "child identity/exit"):
            sut.validate_process_review(self.binding)


if __name__ == "__main__":
    unittest.main(verbosity=2)
