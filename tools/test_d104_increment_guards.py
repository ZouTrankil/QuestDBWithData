"""Pure D104 increment guards; file evidence only, no DB/native/HTTP calls."""
from __future__ import annotations

import copy
import io
import json
import math
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import prepare_d104_macro_core_increment_isolated as sut
import test_d104_fixture_guards as old_samples
import test_d104_preflight_guards as samples


def artifact(name):
    return json.loads((sut.DIRECTORY / name).read_text(encoding="utf-8-sig"), object_pairs_hook=sut.base.unique_json)


def gate():
    return {"protocol_version": 1, "task_id": "D104", "decision": "accepted_for_bounded_source_increment", "source_month": "202608",
            "source_rows": 5, "source_INSERT_attempts": 5, "new_DDL": 0, "private_output_writes": 0, "formal_writes": 0, "automatic_retry": False}


def result():
    value = old_samples.result()
    value.update(increment_admission={"path": "pure", "sha256": "a" * 64}, initial_fixture={"path": "pure", "sha256": "b" * 64},
                 java_initial={"path": "pure", "sha256": "c" * 64}, terminal_review={"path": "pure", "sha256": "d" * 64})
    return value


class AdmissionScopeTests(unittest.TestCase):
    def test_exact_five_august_source_only_scope(self):
        sut.validate_admission_scope(gate())

    def test_other_decision_task_protocol_month_or_retry_rejected(self):
        for field, wrong in (("decision", "accepted"), ("task_id", "D103"), ("protocol_version", True), ("protocol_version", 2), ("source_month", "202607"), ("automatic_retry", True)):
            value = gate(); value[field] = wrong
            with self.subTest(field=field, wrong=wrong), self.assertRaises(RuntimeError):
                sut.validate_admission_scope(value)

    def test_write_budgets_require_exact_int_not_bool(self):
        for field in ("source_rows", "source_INSERT_attempts", "new_DDL", "private_output_writes", "formal_writes"):
            for wrong in (None, False, True, 1.0, -1, 6):
                value = gate(); value[field] = wrong
                with self.subTest(field=field, wrong=wrong), self.assertRaises(RuntimeError):
                    sut.validate_admission_scope(value)

    def test_missing_budget_rejected(self):
        value = gate(); del value["new_DDL"]
        with self.assertRaises(RuntimeError):
            sut.validate_admission_scope(value)

    def test_noexecute_before_any_admission_native_or_connection(self):
        args = MagicMock(); args.execute_isolated = False
        with patch.object(sut, "admission_inputs") as admission, patch.object(sut.base, "PrivateTarget") as target, patch.object(sut, "inspect_ledger") as ledger, self.assertRaises(RuntimeError):
            sut.run(args, sut.OUTPUT, {})
        admission.assert_not_called(); target.assert_not_called(); ledger.assert_not_called()

    def test_only_five_fixed_august_claims_exist(self):
        for table in sut.AUGUST_TABLES:
            self.assertIn("august-dml-" + table, sut.claim_path(table).name)
            self.assertNotEqual(sut.claim_path(table), sut.base.claim_path("DML", table))
        for table in ("cn_gdp", sut.audit.TARGET, sut.base.PRIVATE_OUTPUT, "etf_daily", "cn_cpi;DROP TABLE x"):
            with self.subTest(table=table), self.assertRaises(RuntimeError):
                sut.claim_path(table)

    def test_explicit_utc_timestamp_only(self):
        self.assertEqual(sut.instant("2026-10-06T16:04:24Z"), sut.instant("2026-10-06T16:04:24+00:00"))
        for value in (None, "2026-10-06T16:04:24", "2026-10-07T00:04:24+08:00"):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.instant(value)


class ActualFileBindingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.preflight = artifact("macro-core-readonly-preflight-20261007.json")
        cls.initial = artifact("source-fixture-initial-after-known-partial-20261007.json")
        cls.java = artifact("java-initial-acceptance-20261007.json")
        cls.terminal = artifact("initial-java-process-and-ledger-review-20261007.json")
        cls.historical = artifact("initial-java-terminal-ledger-20261007.json")
        cls.whole = sut.base.captured_sources(cls.preflight)
        cls.initial_rows = sut.base.initial_sources(cls.whole, cls.preflight["original_models"])

    def test_real_initial_23_rows_and_exact_java_fingerprint(self):
        self.assertEqual(sum(map(len, self.initial_rows.values())), 23)
        self.assertEqual(sut.java_raw_fingerprint(self.initial_rows, self.preflight["original_models"], list(sut.base.INITIAL_MONTHS)), self.java["source"]["rawFingerprint"])

    def test_real_six_full_schema_hashes_match_actual_java(self):
        sut.validate_java_source(self.java["source"]["snapshot"], self.initial["private_after"], self.preflight["original_models"])

    def test_real_source_counter_type_and_schema_drift_rejected(self):
        for field, wrong in (("physicalTxn", 2), ("pendingRows", True), ("schemaHash", "0" * 64), ("directory", "different~99")):
            value = copy.deepcopy(self.java["source"]["snapshot"]); value["sources"][0][field] = wrong
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_java_source(value, self.initial["private_after"], self.preflight["original_models"])

    def test_actual_java_initial_oracle_and_roles_match(self):
        sut.validate_java_initial(self.java, {"path": str(sut.INITIAL), "sha256": self.java["fixture_sha256"]}, self.preflight)

    def test_actual_java_nullable_slots_distinct_from_nonnull_bits(self):
        self.assertEqual((self.java["key_and_full_field_comparisons"], self.java["nullable_double_slot_comparisons"], self.java["exact_double_bit_comparisons"]), (18, 16, 15))
        value = copy.deepcopy(self.java); value["exact_double_bit_comparisons"] = 16
        with self.assertRaises(RuntimeError):
            sut.validate_java_initial(value, {"path": str(sut.INITIAL), "sha256": self.java["fixture_sha256"]}, self.preflight)

    def test_actual_terminal_native_executor_junit_and_sha_binding(self):
        self.assertEqual(sut.validate_terminal(self.terminal, self.terminal["java_receipt"], self.java), self.historical)

    def test_actual_terminal_wrong_count_or_sha_rejected(self):
        value = copy.deepcopy(self.terminal); value["retained_leases"] = False
        with self.assertRaises(RuntimeError):
            sut.validate_terminal(value, self.terminal["java_receipt"], self.java)
        value = copy.deepcopy(self.terminal); value["executor_completion"]["sha256"] = "0" * 64
        with self.assertRaises(RuntimeError):
            sut.validate_terminal(value, self.terminal["java_receipt"], self.java)

    def test_actual_eight_run_terminal_ledger_frozen_params_match(self):
        snapshot = {key: self.historical[key] for key in sut.SQLITE_TABLES}
        sut.validate_ledger(snapshot, self.historical, self.java)
        self.assertEqual(len(snapshot["runs"]), 8); self.assertEqual(len(snapshot["entries"]), 18)
        self.assertEqual(len(snapshot["events"]), 72); self.assertEqual(snapshot["leases"], [])

    def test_any_one_of_five_original_ledger_tables_changed_refused(self):
        for table in sut.SQLITE_TABLES:
            snapshot = {key: copy.deepcopy(self.historical[key]) for key in sut.SQLITE_TABLES}
            snapshot[table].append({"changed": True})
            with self.subTest(table=table), self.assertRaises(RuntimeError):
                sut.validate_ledger(snapshot, self.historical, self.java)

    def test_actual_source_version_matches_original_frozen_request(self):
        run = next(value for value in self.historical["runs"] if value["id"] == self.java["first"]["result"]["runId"])
        frozen = json.loads(run["frozen_json"])
        self.assertEqual(sut.java_source_version(self.java["source"]["snapshot"]), frozen["parameters"]["source_version"])

    def test_actual_august_is_five_rows_no_gdp(self):
        rows = {table: [row for row in self.whole[table] if sut.audit.month_key(row[self.preflight["original_models"][table]["timestamp_col"]], table != "cn_gdp") == "202608"] for table in sut.audit.SOURCES}
        self.assertEqual([len(rows[table]) for table in sut.audit.SOURCES], [1, 1, 1, 1, 0, 1])
        self.assertEqual(sum(len(rows[table]) * len(self.preflight["original_models"][table]["schema"]) for table in rows), 118)


class AdmittedCodeBindingTests(unittest.TestCase):
    def _inputs(self):
        paths = [Path(sut.__file__).resolve(), sut.GUARDS.resolve(), *(Path(module.__file__).resolve() for module in (sut.continued, sut.base, sut.audit, sut.audit.transport, sut.base.native))]
        review = {"task_id": "D104", "status": "PASS", "recommendation": "accepted_for_bounded_source_increment", "blockers": [],
                  "bindings": [{"path": str(path.relative_to(sut.audit.REPO)), "absolute_path": str(path), "sha256": sut.audit.digest(path)} for path in paths]}
        value = gate(); value.update(script={"path": str(paths[0]), "sha256": sut.audit.digest(paths[0])}, independent_static_review={"path": "pure", "sha256": "a" * 64})
        return value, review

    def test_exact_admitted_script_guards_and_five_helpers(self):
        value, review = self._inputs()
        with patch.object(sut, "load_binding", return_value=review) as load:
            self.assertEqual(sut.validate_admitted_code(value), review)
        load.assert_called_once_with(value["independent_static_review"])

    def test_wrong_script_sha_before_independent_review_load(self):
        value, _review = self._inputs(); value["script"]["sha256"] = "0" * 64
        with patch.object(sut, "load_binding") as load, self.assertRaises(RuntimeError):
            sut.validate_admitted_code(value)
        load.assert_not_called()

    def test_wrong_script_path_before_independent_review_load(self):
        value, _review = self._inputs(); value["script"] = {"path": str(Path(sut.base.__file__).resolve()), "sha256": sut.audit.digest(sut.base.__file__)}
        with patch.object(sut, "load_binding") as load, self.assertRaises(RuntimeError):
            sut.validate_admitted_code(value)
        load.assert_not_called()

    def test_static_review_sha_tampering_refused(self):
        value, _review = self._inputs(); path = sut.DIRECTORY / "coordinator-known-partial-continuation-static-review-20261007.json"
        value["independent_static_review"] = {"path": str(path), "sha256": "0" * 64}
        with self.assertRaises(RuntimeError):
            sut.validate_admitted_code(value)

    def test_static_review_missing_helper_wrong_sha_or_duplicate_refused(self):
        for change in ("missing", "sha", "duplicate", "path"):
            value, review = self._inputs()
            if change == "missing":
                review["bindings"].pop()
            elif change == "sha":
                review["bindings"][-1]["sha256"] = "0" * 64
            elif change == "duplicate":
                review["bindings"].append(copy.deepcopy(review["bindings"][0]))
            else:
                review["bindings"][-1]["absolute_path"] = str(Path(sut.__file__).resolve())
            with patch.object(sut, "load_binding", return_value=review), self.subTest(change=change), self.assertRaises(RuntimeError):
                sut.validate_admitted_code(value)

    def test_static_review_wrong_task_decision_or_blockers_refused(self):
        for field, wrong in (("task_id", "D103"), ("status", "PENDING"), ("recommendation", "accepted_for_other_stage"), ("blockers", ["unresolved"])):
            value, review = self._inputs(); review[field] = wrong
            with patch.object(sut, "load_binding", return_value=review), self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_admitted_code(value)

    def test_guard_source_binding_uses_tools_not_commands_scope(self):
        value, review = self._inputs(); value["guards"] = {"path": str(sut.GUARDS.resolve()), "sha256": sut.audit.digest(sut.GUARDS)}
        with patch.object(sut, "load_binding", return_value=review):
            self.assertEqual(sut.validate_admitted_code(value), review)

    def test_wrong_guard_source_path_or_sha_refused(self):
        for change in ("path", "sha"):
            value, review = self._inputs(); value["guards"] = {"path": str(sut.GUARDS.resolve()), "sha256": sut.audit.digest(sut.GUARDS)}
            value["guards"][change if change == "path" else "sha256"] = str(Path(sut.base.__file__).resolve()) if change == "path" else "0" * 64
            with patch.object(sut, "load_binding", return_value=review), self.subTest(change=change), self.assertRaises(RuntimeError):
                sut.validate_admitted_code(value)


class LedgerAndProducerTests(unittest.TestCase):
    def _connection(self, snapshot):
        connection = MagicMock()
        def execute(sql):
            response = MagicMock()
            if sql.startswith("SELECT"):
                key = next(key for key, (table, _order) in sut.SQLITE_TABLES.items() if " FROM " + table + " " in sql)
                response.fetchall.return_value = copy.deepcopy(snapshot[key])
            return response
        connection.execute.side_effect = execute
        return connection

    def test_sqlite_only_mode_ro_query_only_five_bounded_selects_and_close(self):
        historical = artifact("initial-java-terminal-ledger-20261007.json")
        snapshot = {key: historical[key] for key in sut.SQLITE_TABLES}
        connection = self._connection(snapshot)
        with patch.object(sut.sqlite3, "connect", return_value=connection) as connect:
            self.assertEqual(sut.inspect_ledger(), snapshot)
        self.assertIn("?mode=ro", connect.call_args.args[0]); self.assertTrue(connect.call_args.kwargs["uri"])
        sql = [call.args[0] for call in connection.execute.call_args_list]
        self.assertEqual(sql[:2], ["PRAGMA query_only=ON", "BEGIN"])
        self.assertEqual(len([value for value in sql if value.startswith("SELECT") and value.endswith("LIMIT 1001")]), 5)
        connection.rollback.assert_called_once(); connection.close.assert_called_once()

    def test_scoped_ledger_only_before_connection(self):
        with patch.object(sut.sqlite3, "connect") as connect, self.assertRaises(RuntimeError):
            sut.inspect_ledger(Path(__file__))
        connect.assert_not_called()

    def test_in_doubt_or_retained_lease_refused_and_connection_closed(self):
        historical = artifact("initial-java-terminal-ledger-20261007.json")
        for changed in ("entries", "leases"):
            snapshot = {key: copy.deepcopy(historical[key]) for key in sut.SQLITE_TABLES}
            if changed == "entries":
                snapshot[changed][0]["state"] = "IN_DOUBT"
            else:
                snapshot[changed].append({"id": "retained"})
            connection = self._connection(snapshot)
            with patch.object(sut.sqlite3, "connect", return_value=connection), self.subTest(changed=changed), self.assertRaises(RuntimeError):
                sut.inspect_ledger()
            connection.close.assert_called_once()

    def test_ledger_cap_refused_and_connection_closed(self):
        historical = artifact("initial-java-terminal-ledger-20261007.json")
        snapshot = {key: copy.deepcopy(historical[key]) for key in sut.SQLITE_TABLES}; snapshot["events"] = [{"id": "x"}] * 1001
        connection = self._connection(snapshot)
        with patch.object(sut.sqlite3, "connect", return_value=connection), self.assertRaises(RuntimeError):
            sut.inspect_ledger()
        connection.close.assert_called_once()

    def test_original_jvm_alive_unknown_or_earlier_birth_refused(self):
        java = {"jvm_pid": 47260, "jvm_birth_utc": "2026-10-06T16:42:49.533552Z"}
        for birth in (java["jvm_birth_utc"], None, "2026-10-06T16:41:00Z"):
            with patch.object(sut.base, "native_query", return_value={"matches": [{"pid": java["jvm_pid"], "birth_utc": birth}]}), self.subTest(birth=birth), self.assertRaises(RuntimeError):
                sut.current_java_absent(java)

    def test_same_pid_later_birth_is_original_identity_absence(self):
        java = {"jvm_pid": 47260, "jvm_birth_utc": "2026-10-06T16:42:49.533552Z"}
        with patch.object(sut.base, "native_query", return_value={"matches": [{"pid": java["jvm_pid"], "birth_utc": "2026-10-06T16:45:00Z"}]}):
            self.assertFalse(sut.current_java_absent(java)["original_identity_present"])


class FullReadClosureTests(unittest.TestCase):
    def _frontier(self):
        current = {table: {"exists": True, "generation": 1} for table in sut.audit.SOURCES}
        outputs = {table: {"exists": table == sut.base.PRIVATE_OUTPUT, "generation": 4} for table in sut.base.PROTECTED}
        return current, outputs, {key: [] for key in sut.SQLITE_TABLES}

    def test_last_full_eight_table_vector_then_fresh_ledger(self):
        current, outputs, ledger = self._frontier(); expected = {**current, **outputs}; events = []
        def state(_reader, table):
            events.append(table); return expected[table]
        def snapshot():
            events.append("ledger"); return ledger
        with patch.object(sut.continued, "private_state", side_effect=state), patch.object(sut, "inspect_ledger", side_effect=snapshot):
            self.assertEqual(sut.final_protected_frontier(MagicMock(), current, outputs, ledger), (expected, ledger))
        self.assertEqual(events, [*sut.audit.SOURCES, *sut.base.PROTECTED, "ledger"])

    def test_final_source_or_protected_target_generation_drift_refused(self):
        for table in (*sut.audit.SOURCES, *sut.base.PROTECTED):
            current, outputs, ledger = self._frontier(); fresh = copy.deepcopy({**current, **outputs}); fresh[table]["generation"] += 1
            with patch.object(sut.continued, "private_state", side_effect=lambda _reader, name: fresh[name]), patch.object(sut, "inspect_ledger") as inspect, self.subTest(table=table), self.assertRaises(RuntimeError):
                sut.final_protected_frontier(MagicMock(), current, outputs, ledger)
            inspect.assert_not_called()

    def test_final_ledger_changed_after_fields_refused(self):
        current, outputs, ledger = self._frontier(); fresh = {**current, **outputs}; changed = copy.deepcopy(ledger); changed["events"].append({"drift": True})
        with patch.object(sut.continued, "private_state", side_effect=lambda _reader, name: fresh[name]), patch.object(sut, "inspect_ledger", return_value=changed), self.assertRaises(RuntimeError):
            sut.final_protected_frontier(MagicMock(), current, outputs, ledger)

    def _output(self, drift=False):
        java = artifact("java-initial-acceptance-20261007.json"); preflight = artifact("macro-core-readonly-preflight-20261007.json")
        model = preflight["original_models"][sut.audit.TARGET]; columns = samples.columns(model); target = java["actual_target"]
        p = {"id": target["tableId"], "directoryName": target["directory"], "table_txn": target["physicalTxn"], "table_row_count": 2,
             "partitionBy": "YEAR", "designatedTimestamp": "month", "walEnabled": True, "dedup": True, "table_suspended": False, "wal_pending_row_count": 0}
        w = {"sequencerTxn": 4, "writerTxn": 4, "bufferedTxnSize": 0, "suspended": False}
        state = {"exists": True, "physical": p, "wal": w, "actual_select_count": 2, "settled": True}
        changed = copy.deepcopy(state)
        if drift:
            changed["physical"]["table_txn"] += 1
        reader = MagicMock(); reader.records.side_effect = [columns, preflight["expected_oracle_rows"][:2]]
        return java, preflight, reader, [{"exists": False}, state, {"exists": False}, changed]

    def test_output_rows_bracketed_by_fresh_two_table_vector(self):
        java, preflight, reader, states = self._output()
        with patch.object(sut.continued, "private_state", side_effect=states) as observe:
            proof = sut.output_frontier(reader, java, preflight)
        self.assertTrue(proof["complete_output_parity"]["passed"]); self.assertEqual(observe.call_count, 4)
        self.assertTrue(all(call.args[0].split()[0] == "SELECT" for call in reader.records.call_args_list))

    def test_output_counter_drift_after_full_rows_refused(self):
        java, preflight, reader, states = self._output(drift=True)
        with patch.object(sut.continued, "private_state", side_effect=states), self.assertRaises(RuntimeError):
            sut.output_frontier(reader, java, preflight)
        self.assertEqual(reader.records.call_count, 2)

    def test_full_source_bit_change_even_one_ulp_refused(self):
        model = samples.models()["cn_cpi"]; rows = samples.sample()["cn_cpi"][:2]; altered = copy.deepcopy(rows)
        altered[0]["nt_yoy"] = math.nextafter(rows[0]["nt_yoy"], math.inf)
        with self.assertRaises(RuntimeError):
            sut.base.strict_compare(altered, rows, "cn_cpi", model)

    def test_nullable_slot_proof_separates_null_from_rawbits(self):
        model = samples.models()["cn_cpi"]; rows = samples.sample()["cn_cpi"][:2]; rows[0]["nt_yoy"] = None
        proof = sut.base.strict_compare(copy.deepcopy(rows), rows, "cn_cpi", model)
        self.assertEqual(proof["nullable_double_slot_comparisons"], 24)
        self.assertEqual(proof["nonnull_double_rawbit_comparisons"], 23)
        self.assertEqual(proof["null_double_comparisons"], 1)


class DurableSingleInsertTests(unittest.TestCase):
    def _attempt(self, body=b'{"dml":"OK","updated":1}', failure=None, old_claim=False, status=200, fail_raw=False):
        value = result(); target = MagicMock(); target.verify.return_value = old_samples.identity()
        response = MagicMock(); response.status = status; response.__enter__.return_value = response; response.read.return_value = body
        events = []
        def journal(_path, record):
            events.append(("journal", copy.deepcopy(record)))
        def claim(_path, record):
            events.append(("claim", copy.deepcopy(record)))
            if old_claim:
                raise FileExistsError("retained claim")
        def raw(path, record):
            events.append(("raw", record))
            if fail_raw:
                raise OSError("durable raw response failed")
            return {"path": str(path), "sha256": "c" * 64, "bytes": len(record)}
        def http(*_args, **_kwargs):
            self.assertEqual(value["operations"][0]["ack"], "UNKNOWN")
            events.append("http")
            if failure is not None:
                raise failure
            return response
        with patch.object(sut.base, "producer_identity", return_value=old_samples.producer()), patch.object(sut.audit, "save_new", side_effect=claim), patch.object(sut.base, "save_progress", side_effect=journal), \
             patch.object(sut.audit, "digest", return_value="d" * 64), patch.object(sut.base, "save_raw", side_effect=raw), patch.object(sut, "urlopen", side_effect=http) as send:
            try:
                sut.submit_once(target, "cn_cpi", samples.sample()["cn_cpi"][2:], samples.models()["cn_cpi"], sut.OUTPUT, value, MagicMock())
                error = None
            except BaseException as caught:
                error = caught
        return value, events, send, error, response

    def test_unknown_intent_before_http_and_raw_fsync_before_ack(self):
        value, events, send, error, response = self._attempt()
        self.assertIsNone(error); self.assertEqual(send.call_count, 1); self.assertEqual(send.call_args.kwargs["timeout"], 20)
        claim_index = next(i for i, event in enumerate(events) if isinstance(event, tuple) and event[0] == "claim")
        raw_index = next(i for i, event in enumerate(events) if isinstance(event, tuple) and event[0] == "raw")
        ack_index = next(i for i, event in enumerate(events) if isinstance(event, tuple) and event[0] == "journal" and event[1].get("ack") == "ACKNOWLEDGED")
        self.assertLess(claim_index, events.index("http")); self.assertEqual(events[claim_index][1]["ack"], "UNKNOWN"); self.assertLess(raw_index, ack_index)
        self.assertEqual((value["attempted_operations"], value["submitted_source_rows"], value["acknowledged_operations"], value["acknowledged_source_rows"]), (1, 1, 1, 1))
        response.__exit__.assert_called_once()

    def test_native_exact_dml_without_updated_is_known_ack(self):
        value, _events, send, error, _response = self._attempt(body=b'{"dml":"OK"}')
        self.assertIsNone(error); self.assertEqual(send.call_count, 1); self.assertEqual(value["operations"][0]["ack"], "ACKNOWLEDGED")

    def test_timeout_unknown_no_retry_or_raw_ack_invention(self):
        value, events, send, error, _response = self._attempt(failure=TimeoutError("UNKNOWN"))
        self.assertIsInstance(error, TimeoutError); self.assertEqual(send.call_count, 1)
        self.assertEqual((value["attempted_operations"], value["submitted_source_rows"], value["acknowledged_operations"]), (1, 1, 0))
        self.assertEqual(value["operations"][0]["ack"], "UNKNOWN"); self.assertFalse(any(isinstance(event, tuple) and event[0] == "raw" for event in events))

    def test_wrong_ddl_or_row_count_or_duplicate_json_unknown(self):
        for body in (b'{"ddl":"OK"}', b'{"dml":"OK","updated":2}', b'{"dml":"OK","updated":true}', b'{"dml":"OK","extra":1}', b'{"dml":"OK","dml":"OK"}', b'not-json'):
            value, events, send, error, _response = self._attempt(body=body)
            with self.subTest(body=body):
                self.assertIsNotNone(error); self.assertEqual(send.call_count, 1); self.assertEqual(value["operations"][0]["ack"], "UNKNOWN")
                self.assertTrue(any(isinstance(event, tuple) and event[0] == "raw" for event in events)); self.assertEqual(value["acknowledged_operations"], 0)

    def test_http_error_raw_preserved_no_ack_or_retry(self):
        error = HTTPError("http://private/exec", 500, "bad", {}, io.BytesIO(b'{"error":"failed"}'))
        value, events, send, caught, _response = self._attempt(failure=error)
        self.assertIsInstance(caught, RuntimeError); self.assertEqual(send.call_count, 1); self.assertEqual(value["operations"][0]["http_status"], 500)
        self.assertEqual(value["operations"][0]["ack"], "UNKNOWN"); self.assertTrue(any(isinstance(event, tuple) and event[0] == "raw" for event in events))

    def test_durable_raw_write_failure_never_promotes_ack(self):
        value, _events, send, error, _response = self._attempt(fail_raw=True)
        self.assertIsInstance(error, OSError); self.assertEqual(send.call_count, 1); self.assertEqual(value["operations"][0]["ack"], "UNKNOWN")

    def test_existing_august_claim_never_sends(self):
        value, _events, send, error, _response = self._attempt(old_claim=True)
        self.assertIsInstance(error, FileExistsError); send.assert_not_called(); self.assertEqual(value["operations"], [])

    def test_gdp_output_wrongmonth_multibatch_duplicate_or_budget_rejected(self):
        models = samples.models(); rows = samples.sample()
        cases = [("cn_gdp", rows["cn_gdp"], models["cn_gdp"], result()), (sut.audit.TARGET, [], models[sut.audit.TARGET], result()),
                 ("cn_cpi", rows["cn_cpi"][:1], models["cn_cpi"], result()), ("cn_cpi", rows["cn_cpi"][1:], models["cn_cpi"], result())]
        duplicate = result(); duplicate["operations"] = [{"table": "cn_cpi"}]
        full = result(); full["operations"] = [{"table": "other"}] * 5
        cases.extend([("cn_cpi", rows["cn_cpi"][2:], models["cn_cpi"], duplicate), ("cn_cpi", rows["cn_cpi"][2:], models["cn_cpi"], full)])
        for table, batch, model, value in cases:
            with patch.object(sut, "urlopen") as send, patch.object(sut.audit, "save_new") as claim, self.subTest(table=table, batch=len(batch)), self.assertRaises(RuntimeError):
                sut.submit_once(MagicMock(), table, batch, model, sut.OUTPUT, value, MagicMock())
            send.assert_not_called(); claim.assert_not_called()

    def test_cancel_or_boundary_failure_prevents_intent_and_http(self):
        for where in ("cancel", "boundary"):
            boundary = MagicMock(side_effect=RuntimeError("boundary changed")) if where == "boundary" else MagicMock()
            with patch.object(sut.audit, "check_cancel", side_effect=RuntimeError("cancel") if where == "cancel" else None), patch.object(sut.audit, "save_new") as save, patch.object(sut, "urlopen") as send, self.subTest(where=where), self.assertRaises(RuntimeError):
                sut.submit_once(MagicMock(), "cn_cpi", samples.sample()["cn_cpi"][2:], samples.models()["cn_cpi"], sut.OUTPUT, result(), boundary)
            save.assert_not_called(); send.assert_not_called()

    def test_service_or_producer_identity_drift_prevents_intent(self):
        for where in ("service", "producer"):
            target = MagicMock(); service = old_samples.identity(); producer = old_samples.producer()
            if where == "service":
                service["pid"] += 1
            else:
                producer["birth_utc"] = "2026-10-06T16:06:00Z"
            target.verify.return_value = service
            with patch.object(sut.base, "producer_identity", return_value=producer), patch.object(sut.audit, "save_new") as save, patch.object(sut, "urlopen") as send, self.subTest(where=where), self.assertRaises(RuntimeError):
                sut.submit_once(target, "cn_cpi", samples.sample()["cn_cpi"][2:], samples.models()["cn_cpi"], sut.OUTPUT, result(), MagicMock())
            save.assert_not_called(); send.assert_not_called()


if __name__ == "__main__":
    unittest.main()
