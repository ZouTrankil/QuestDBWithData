"""Pure D104 fixture guards: zero actual DB, HTTP, native or provider calls."""
from __future__ import annotations

import copy
from datetime import datetime, timezone
import io
import json
import math
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import prepare_d104_macro_core_isolated as sut
import test_d104_preflight_guards as samples

BIRTH = "2026-10-06T16:04:24.4330550Z"


def startup():
    return {"task_id": "D104", "pid": 43084, "data_root": str(sut.ROOT.resolve()), "http_port": 19040, "pg_port": 18852,
            "birth_utc": BIRTH, "arguments": ["-m", "io.questdb/io.questdb.ServerMain", "-d", str(sut.ROOT.resolve())]}


def identity():
    return {"pid": 43084, "birth_utc": BIRTH, "data_root": str(sut.ROOT.resolve()), "host": "127.0.0.1", "http_port": 19040, "pg_port": 18852}


def producer():
    return {"pid": 12345, "parent_pid": 1234, "name": "python.exe", "birth_utc": "2026-10-06T16:05:00.0000000Z"}


def result():
    return {"invocation_id": "pure-test", "operations": [], "attempted_operations": 0, "acknowledged_operations": 0,
            "submitted_source_rows": 0, "acknowledged_source_rows": 0, "private_target_attestation": identity(),
            "producer_identity": producer(), "preflight_evidence": {"path": "pure", "sha256": "a" * 64}, "startup_evidence": {"path": "pure", "sha256": "b" * 64}}


def listeners():
    return [{"port": port, "address": "127.0.0.1", "pid": 43084, "name": "java.exe", "birth": BIRTH, "command": "pure-native-argv"} for port in (19040, 18852)]


class TargetProofTests(unittest.TestCase):
    def test_exact_startup(self):
        self.assertEqual(sut.validate_startup(startup(), sut.ROOT.resolve(), 43084), BIRTH)

    def test_startup_task_root_port_pid_birth_rejected(self):
        for key, value in (("task_id", "D103"), ("pid", True), ("http_port", 9000), ("pg_port", 8812), ("data_root", str(sut.REPO)), ("birth_utc", None)):
            proof = startup(); proof[key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                sut.validate_startup(proof, sut.ROOT.resolve(), 43084)

    def test_startup_module_missing_duplicate_root_rejected(self):
        for args in (["-d", str(sut.ROOT)], ["io.questdb/io.questdb.ServerMain", "-d", str(sut.ROOT), "-d", str(sut.ROOT)]):
            proof = startup(); proof["arguments"] = args
            with self.subTest(args=args), self.assertRaises(RuntimeError):
                sut.validate_startup(proof, sut.ROOT.resolve(), 43084)

    def test_exact_listeners(self):
        with patch.object(sut.native, "split_windows_command_line", return_value=startup()["arguments"]):
            self.assertEqual(sut.validate_listeners(listeners(), sut.ROOT.resolve(), 43084, BIRTH), identity())

    def test_listener_mismatch_and_reused_birth_rejected(self):
        for key, value in (("pid", 123), ("port", 9000), ("address", "0.0.0.0"), ("name", "python.exe"), ("birth", "2026-10-06T16:05:00Z")):
            records = listeners(); records[0][key] = value
            with patch.object(sut.native, "split_windows_command_line", return_value=startup()["arguments"]), self.subTest(key=key), self.assertRaises(RuntimeError):
                sut.validate_listeners(records, sut.ROOT.resolve(), 43084, BIRTH)

    def test_listener_root_and_duplicate_listener_rejected(self):
        with patch.object(sut.native, "split_windows_command_line", return_value=["io.questdb/io.questdb.ServerMain", "-d", str(sut.REPO)]), self.assertRaises(RuntimeError):
            sut.validate_listeners(listeners(), sut.ROOT.resolve(), 43084, BIRTH)
        with self.assertRaises(RuntimeError):
            sut.validate_listeners(listeners() + [listeners()[0]], sut.ROOT.resolve(), 43084, BIRTH)

    def test_producer_known_current_python_identity(self):
        with patch.object(sut.os, "getpid", return_value=12345), patch.object(sut, "native_query", return_value=producer()):
            self.assertEqual(sut.producer_identity(), producer())

    def test_producer_wrong_pid_unknown_birth_and_non_python_rejected(self):
        for key, value in (("pid", 123), ("birth_utc", None), ("name", "node.exe"), ("parent_pid", True)):
            proof = producer(); proof[key] = value
            with patch.object(sut.os, "getpid", return_value=12345), patch.object(sut, "native_query", return_value=proof), self.subTest(key=key), self.assertRaises(RuntimeError):
                sut.producer_identity()


class SourceAndSqlTests(unittest.TestCase):
    def test_initial23_real_shape_and294fields(self):
        models = samples.models()
        initial = sut.initial_sources(samples.sample(), models)
        self.assertEqual([len(initial[table]) for table in sut.audit.SOURCES], [2, 2, 2, 2, 1, 14])
        self.assertEqual(sum(len(rows) * len(models[table]["schema"]) for table, rows in initial.items()), 294)

    def test_missing_initial_source_row_rejected(self):
        rows = samples.sample(); rows["cn_cpi"].pop(0)
        with self.assertRaises(RuntimeError):
            sut.initial_sources(rows, samples.models())

    def test_create_clones_complete_source_year_wal_dedup(self):
        for table, model in samples.models().items():
            if table not in sut.audit.SOURCES:
                continue
            sql = sut.create_sql(table, model)
            self.assertTrue(sql.startswith("CREATE TABLE " + table))
            self.assertIn("PARTITION BY YEAR WAL DEDUP UPSERT KEYS(" + model["timestamp_col"] + ")", sql)
            for field, kind in model["schema"].items():
                self.assertIn(field + " " + kind, sql)

    def test_output_or_formal_ddl_target_rejected(self):
        model = samples.models()["cn_cpi"]
        for table in (sut.audit.TARGET, sut.PRIVATE_OUTPUT, "other", "cn_cpi; DROP TABLE x"):
            with self.subTest(table=table), self.assertRaises(RuntimeError):
                sut.create_sql(table, model)

    def test_insert_full_fields_finite_rows_and_null(self):
        model = samples.models()["cn_cpi"]
        rows = samples.sample()["cn_cpi"][:2]; rows[0]["cnt_val"] = None
        sql = sut.insert_sql("cn_cpi", rows, model)
        self.assertIn("NULL", sql)
        self.assertTrue(sql.startswith("INSERT INTO cn_cpi (" + ','.join(model["schema"]) + ") VALUES "))

    def test_empty_overbudget_unknown_or_output_insert_rejected(self):
        model = samples.models()["cn_cpi"]; rows = samples.sample()["cn_cpi"]
        for table, value in (("cn_cpi", []), ("cn_cpi", rows * 5), (sut.audit.TARGET, rows), ("other", rows)):
            with self.subTest(table=table), self.assertRaises(RuntimeError):
                sut.insert_sql(table, value, model)

    def test_sql_nonfinite_boolean_integer_double_rejected(self):
        for value in (True, 1, math.inf, math.nan):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.sql_value(value, "DOUBLE")

    def test_string_literal_escape_and_null_key_rejected(self):
        self.assertEqual(sut.sql_value("a'b", "STRING"), "'a''b'")
        with self.assertRaises(RuntimeError):
            sut.sql_value(None, "TIMESTAMP")

    def test_strict_source_fullbits_and_nullable_unchanged(self):
        rows = samples.sample()["cn_cpi"]; model = samples.models()["cn_cpi"]
        proof = sut.strict_compare(copy.deepcopy(rows), rows, "cn_cpi", model)
        self.assertEqual((proof["full_field_comparisons"], proof["double_rawbit_comparisons"]), (39, 36))

    def test_strict_source_oneulp_or_null_difference_rejected(self):
        rows = samples.sample()["cn_cpi"]; model = samples.models()["cn_cpi"]
        for value in (math.nextafter(1.0, math.inf), None):
            actual = copy.deepcopy(rows); actual[0]["nt_yoy"] = value
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.strict_compare(actual, rows, "cn_cpi", model)

    def test_strict_source_blank_null_duplicate_key_rejected(self):
        rows = samples.sample()["cn_cpi"]; model = samples.models()["cn_cpi"]
        for value in (None, "", rows[1]["month"]):
            actual = copy.deepcopy(rows); actual[0]["month"] = value
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.strict_compare(actual, rows, "cn_cpi", model)

    def test_private_output_reader_whitelist_before_db(self):
        reader = MagicMock()
        with self.assertRaises(RuntimeError):
            sut.private_state(reader, "other")
        with self.assertRaises(RuntimeError):
            sut.source_rows(reader, sut.audit.TARGET, samples.models()["cn_cpi"])
        reader.records.assert_not_called()


class AckAndClaimTests(unittest.TestCase):
    def test_ddl_and_dml_ack_are_separate(self):
        sut.validate_ack({"ddl": "OK"}, "DDL", 0)
        sut.validate_ack({"dml": "OK"}, "DML", 2)
        sut.validate_ack({"dml": "OK", "updated": 2}, "DML", 2)

    def test_wrong_kind_unknown_count_extra_field_rejected(self):
        for payload, kind, rows in (({"ddl": "OK"}, "DML", 2), ({"dml": "OK"}, "DDL", 0), ({"dml": "OK", "updated": True}, "DML", 1), ({"dml": "OK", "updated": 1}, "DML", 2), ({"dml": "OK", "x": 1}, "DML", 2), ([], "DML", 2)):
            with self.subTest(payload=payload), self.assertRaises(RuntimeError):
                sut.validate_ack(payload, kind, rows)

    def test_claim_fixed_by_initial_table_and_kind(self):
        self.assertEqual(sut.claim_path("DML", "cn_cpi"), sut.DIRECTORY / "source-fixture-initial-dml-cn_cpi-20261007.claim.json")
        for kind, table in (("ILP", "cn_cpi"), ("DML", sut.audit.TARGET)):
            with self.subTest(kind=kind, table=table), self.assertRaises(RuntimeError):
                sut.claim_path(kind, table)

    def test_json_duplicate_keys_rejected(self):
        with self.assertRaises(RuntimeError):
            json.loads('{"ack":"UNKNOWN","ack":"ACKNOWLEDGED"}', object_pairs_hook=sut.unique_json)

    def test_execute_flag_false_before_evidence_or_connection(self):
        args = MagicMock(); args.execute_isolated = False
        with patch.object(sut, "load") as load, patch.object(sut, "PrivateTarget") as target, self.assertRaises(RuntimeError):
            sut.run(args, sut.OUTPUT, result())
        load.assert_not_called(); target.assert_not_called()

    def test_deadline_closes_private_connection(self):
        connection = MagicMock()
        with patch.object(sut.time, "monotonic", side_effect=[0, 21]), self.assertRaises(RuntimeError):
            with sut.private_deadline(connection, 20):
                pass
        connection.close.assert_called_once()

    def test_raw_response_new_only_fsync(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as directory:
            path = Path(directory).resolve() / "response.bin"
            proof = sut.save_raw(path, b'{"dml":"OK"}')
            self.assertEqual(path.read_bytes(), b'{"dml":"OK"}')
            self.assertEqual(proof["sha256"], sut.audit.digest(path))
            with self.assertRaises(FileExistsError):
                sut.save_raw(path, b'changed')

    def _submit(self, payload=b'{"dml":"OK","updated":2}', failure=None, kind="DML"):
        state, events = result(), []
        target = MagicMock(); target.verify.return_value = identity()
        response = MagicMock(); response.__enter__.return_value = response; response.status = 200; response.read.return_value = payload
        def open_http(*_args, **_kwargs):
            self.assertEqual(state["operations"][0]["ack"], "UNKNOWN")
            events.append("http")
            if failure:
                raise failure
            return response
        def journal(path, value):
            events.append(("journal", copy.deepcopy(value)))
        def new_claim(path, value):
            events.append(("claim", copy.deepcopy(value)))
        def raw(path, body):
            events.append(("raw", body))
            return {"path": str(path), "sha256": "c" * 64, "bytes": len(body)}
        with patch.object(sut, "producer_identity", return_value=producer()), patch.object(sut.audit, "save_new", side_effect=new_claim), \
             patch.object(sut, "save_progress", side_effect=journal), patch.object(sut.audit, "digest", return_value="d" * 64), \
             patch.object(sut, "save_raw", side_effect=raw), patch.object(sut, "urlopen", side_effect=open_http) as http:
            rows = samples.sample()["cn_cpi"][:2] if kind == "DML" else []
            try:
                sut.submit_once(target, kind, "cn_cpi", samples.models()["cn_cpi"], rows, sut.OUTPUT, state, lambda: events.append("boundary"))
                error = None
            except BaseException as caught:
                error = caught
        return state, events, http.call_count, error, response

    def test_ack_saved_only_after_durable_raw_response(self):
        state, events, attempts, error, response = self._submit()
        self.assertIsNone(error)
        self.assertEqual((attempts, state["submitted_source_rows"], state["acknowledged_source_rows"]), (1, 2, 2))
        claim_index = next(i for i, value in enumerate(events) if isinstance(value, tuple) and value[0] == "claim")
        self.assertEqual(events[claim_index][1]["ack"], "UNKNOWN")
        raw_index = next(i for i, value in enumerate(events) if isinstance(value, tuple) and value[0] == "raw")
        ack_index = next(i for i, value in enumerate(events) if isinstance(value, tuple) and value[0] == "journal" and value[1].get("ack") == "ACKNOWLEDGED")
        self.assertLess(claim_index, events.index("http")); self.assertLess(raw_index, ack_index)
        response.__exit__.assert_called_once()

    def test_unknown_network_failure_oneattempt_no_retry(self):
        state, events, attempts, error, response = self._submit(failure=TimeoutError("unknown transport"))
        self.assertIsInstance(error, TimeoutError)
        self.assertEqual((attempts, state["operations"][0]["ack"], state["acknowledged_operations"]), (1, "UNKNOWN", 0))
        self.assertFalse(any(isinstance(value, tuple) and value[0] == "raw" for value in events))

    def test_wrong_ack_raw_saved_unknown_no_retry(self):
        state, events, attempts, error, response = self._submit(payload=b'{"ddl":"OK"}')
        self.assertIsInstance(error, RuntimeError)
        self.assertEqual((attempts, state["operations"][0]["ack"], state["acknowledged_source_rows"]), (1, "UNKNOWN", 0))
        self.assertTrue(any(isinstance(value, tuple) and value[0] == "raw" for value in events))

    def test_invalid_json_raw_saved_unknown_no_retry(self):
        state, events, attempts, error, response = self._submit(payload=b'not JSON')
        self.assertIsInstance(error, json.JSONDecodeError)
        self.assertEqual((attempts, state["operations"][0]["ack"]), (1, "UNKNOWN"))
        self.assertTrue(any(isinstance(value, tuple) and value[0] == "raw" for value in events))

    def test_http_error_raw_saved_unknown_no_retry(self):
        failure = HTTPError("http://private", 500, "error", {}, io.BytesIO(b'{"error":"failure"}'))
        state, events, attempts, error, response = self._submit(failure=failure)
        self.assertIsInstance(error, RuntimeError)
        self.assertEqual((attempts, state["operations"][0]["ack"], state["operations"][0]["http_status"]), (1, "UNKNOWN", 500))
        self.assertTrue(any(isinstance(value, tuple) and value[0] == "raw" for value in events))

    def test_cancellation_before_claim_or_http(self):
        with patch.object(sut.audit, "check_cancel", side_effect=RuntimeError("cancelled")), patch.object(sut, "urlopen") as http, patch.object(sut.audit, "save_new") as save, self.assertRaises(RuntimeError):
            sut.submit_once(MagicMock(), "DML", "cn_cpi", samples.models()["cn_cpi"], samples.sample()["cn_cpi"][:2], sut.OUTPUT, result(), MagicMock(), "cancel")
        http.assert_not_called(); save.assert_not_called()

    def test_existing_claim_never_resends(self):
        target = MagicMock(); target.verify.return_value = identity()
        state = result()
        with patch.object(sut, "producer_identity", return_value=producer()), patch.object(sut.audit, "save_new", side_effect=FileExistsError("old claim")), patch.object(sut, "urlopen") as http, self.assertRaises(FileExistsError):
            sut.submit_once(target, "DML", "cn_cpi", samples.models()["cn_cpi"], samples.sample()["cn_cpi"][:2], sut.OUTPUT, state, MagicMock())
        http.assert_not_called(); self.assertEqual(state["operations"], [])

    def test_service_or_producer_changed_before_claim(self):
        for which in ("service", "producer"):
            target = MagicMock(); target.verify.return_value = {**identity(), "pid": 7} if which == "service" else identity()
            with patch.object(sut, "producer_identity", return_value={**producer(), "pid": 7} if which == "producer" else producer()), patch.object(sut.audit, "save_new") as save, patch.object(sut, "urlopen") as http, self.subTest(which=which), self.assertRaises(RuntimeError):
                sut.submit_once(target, "DML", "cn_cpi", samples.models()["cn_cpi"], samples.sample()["cn_cpi"][:2], sut.OUTPUT, result(), MagicMock())
            save.assert_not_called(); http.assert_not_called()


if __name__ == "__main__":
    unittest.main()
