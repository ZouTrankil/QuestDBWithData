"""Pure D098 fixture/audit guard tests; all DB, HTTP and CIM calls are mocked."""
from __future__ import annotations

import copy
import importlib
import io
import json
import math
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import MagicMock, patch

sys.dont_write_bytecode = True
TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
sut = importlib.import_module("accept_d098_mv_isolated")


def source_row(index=1):
    values = {field: (1 if kind == "LONG" else 0.125)
              for field, kind in sut.audit.SOURCE_TYPES.items() if field not in {"ts", "symbol"}}
    return {"ts": "2026-09-17T00:00:00.000000Z", "symbol": f"{index:06d}.SZ", **values}


class SelectGuardTests(unittest.TestCase):
    def test_formal_original_multiline_select_is_allowed(self):
        sql = sut.common.VIEW_SELECTS[sut.audit.ALIAS].format(where="WHERE ts >= '2026-09-17'")
        self.assertTrue(sql.startswith("SELECT\n"))
        with patch.object(sut.audit.formal, "qwp", return_value=[]) as execute:
            self.assertEqual([], sut.audit.query(sql))
        execute.assert_called_once_with(sql)

    def test_formal_empty_mutation_and_semicolon_are_rejected_before_io(self):
        with patch.object(sut.audit.formal, "qwp") as execute:
            for sql in ("", " \n\t", "INSERT INTO source VALUES(1)", "REFRESH MATERIALIZED VIEW x FULL",
                        "DROP TABLE x", "SELECT 1;", "SELECT 1; SELECT 2"):
                with self.subTest(sql=sql), self.assertRaises(RuntimeError):
                    sut.audit.query(sql)
            execute.assert_not_called()

    def test_private_multiline_select_uses_private_port_without_mutation_attestation(self):
        target = MagicMock()
        payload = {"columns": [{"name": "n", "type": "LONG"}], "dataset": [[1]]}
        sql = "  SELECT\n count() AS n FROM l2_daily_features"
        with patch.object(sut, "PRIVATE_TARGET", target), \
                patch.object(sut, "urlopen", return_value=io.BytesIO(json.dumps(payload).encode())) as http:
            self.assertEqual([{"n": 1}], sut.qwp(sql))
        self.assertTrue(http.call_args.args[0].full_url.startswith("http://127.0.0.1:19010/exec?"))
        target.verify.assert_not_called()

    def test_private_access_without_attestation_is_rejected_before_http(self):
        with patch.object(sut, "PRIVATE_TARGET", None), patch.object(sut, "urlopen") as http:
            with self.assertRaises(RuntimeError):
                sut.qwp("SELECT 1")
            http.assert_not_called()

    def test_private_mutation_attestation_failure_prevents_http(self):
        target = MagicMock()
        target.verify.side_effect = RuntimeError("private process changed")
        with patch.object(sut, "PRIVATE_TARGET", target), patch.object(sut, "urlopen") as http:
            with self.assertRaises(RuntimeError):
                sut.qwp("CREATE TABLE l2_daily_features(ts TIMESTAMP)")
            target.verify.assert_called_once_with()
            http.assert_not_called()


class PrivateAttestationTests(unittest.TestCase):
    def setUp(self):
        var = (sut.common.REPO_ROOT / "var").resolve()
        self.temporary = tempfile.TemporaryDirectory(prefix="d098-pure-test-", dir=var)
        self.root = Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(var))
        self.addCleanup(self.temporary.cleanup)
        (self.root / "conf").mkdir()
        self.config = self.root / "conf/server.conf"
        self.config.write_text("http.net.bind.to=127.0.0.1:19010\npg.net.bind.to=127.0.0.1:18822\n", encoding="utf-8")
        self.marker = self.root / "d098-fixture.json"
        self.marker.write_text(json.dumps({"task_id": "D098", "data_root": str(self.root),
                                           "fixture_tables": [sut.SOURCE, sut.MV]}), encoding="utf-8")
        self.root_patch = patch.object(sut, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.split_patch = patch.object(sut.common, "split_windows_command_line", side_effect=json.loads)
        self.split_patch.start()
        self.addCleanup(self.split_patch.stop)
        self.run_patch = patch.object(sut.subprocess, "run")
        self.run = self.run_patch.start()
        self.addCleanup(self.run_patch.stop)
        self.set_records(self.records())

    def records(self, pid=12345):
        return [{"port": port, "address": "127.0.0.1", "pid": pid, "name": "java.exe",
                 "command": json.dumps(["java.exe", "io.questdb.ServerMain", "-d", str(self.root)])}
                for port in (19010, 18822)]

    def set_records(self, records):
        self.run.return_value = types.SimpleNamespace(returncode=0, stdout=json.dumps(records))

    def test_same_loopback_process_exact_root_and_dynamic_expected_pid_are_accepted(self):
        target = sut.PrivateTarget(self.root, 12345)
        self.assertEqual({"pid": 12345, "data_root": str(self.root), "address": "127.0.0.1",
                          "http_port": 19010, "pg_port": 18822}, target.identity)
        target.verify()
        self.assertEqual(2, self.run.call_count)

    def test_expected_pid_mismatch_is_rejected(self):
        with self.assertRaises(RuntimeError):
            sut.PrivateTarget(self.root, 99999)

    def test_different_qwp_and_pg_processes_are_rejected(self):
        records = self.records()
        records[1]["pid"] += 1
        self.set_records(records)
        with self.assertRaises(RuntimeError):
            sut.PrivateTarget(self.root, 12345)

    def test_missing_wrong_or_duplicate_listener_port_is_rejected(self):
        for records in (self.records()[:1], self.records() + [self.records()[0]],
                        [{**row, "port": 9000} if index == 0 else row for index, row in enumerate(self.records())]):
            with self.subTest(records=records):
                self.set_records(records)
                with self.assertRaises(RuntimeError):
                    sut.PrivateTarget(self.root, 12345)

    def test_nonloopback_or_wrong_executable_is_rejected(self):
        for field, value in (("address", "0.0.0.0"), ("name", "powershell.exe")):
            with self.subTest(field=field):
                records = self.records()
                records[0][field] = value
                self.set_records(records)
                with self.assertRaises(RuntimeError):
                    sut.PrivateTarget(self.root, 12345)

    def test_missing_duplicate_or_wrong_command_data_root_is_rejected(self):
        for args in (["java.exe"], ["java.exe", "-d"],
                     ["java.exe", "-d", str(self.root), "-d", str(self.root)],
                     ["java.exe", "-d", str(self.root / "wrong-root")]):
            with self.subTest(args=args):
                records = self.records()
                records[0]["command"] = json.dumps(args)
                self.set_records(records)
                with self.assertRaises(RuntimeError):
                    sut.PrivateTarget(self.root, 12345)

    def test_wrong_requested_root_is_rejected_before_cim(self):
        other = self.root / "other"
        other.mkdir()
        with self.assertRaises(RuntimeError):
            sut.PrivateTarget(other, 12345)
        self.run.assert_not_called()

    def test_wrong_config_or_marker_is_rejected(self):
        self.config.write_text("http.net.bind.to=127.0.0.1:9000\npg.net.bind.to=127.0.0.1:8812\n", encoding="utf-8")
        with self.assertRaises(RuntimeError):
            sut.PrivateTarget(self.root, 12345)
        self.run.assert_not_called()
        self.config.write_text("http.net.bind.to=127.0.0.1:19010\npg.net.bind.to=127.0.0.1:18822\n", encoding="utf-8")
        self.marker.write_text(json.dumps({"task_id": "D095"}), encoding="utf-8")
        with self.assertRaises(RuntimeError):
            sut.PrivateTarget(self.root, 12345)

    def test_process_change_after_initial_attestation_is_rejected(self):
        target = sut.PrivateTarget(self.root)
        self.set_records(self.records(pid=12346))
        with self.assertRaises(RuntimeError):
            target.verify()


class StrictCaptureTests(unittest.TestCase):
    def compare(self, actual, expected):
        return sut.strict_compare(actual, expected, sut.audit.SOURCE_FIELDS, ("ts", "symbol"))

    def test_all_fifteen_fields_and_reordered_complete_keys_match(self):
        expected = [source_row(1), source_row(2)]
        self.assertEqual(30, self.compare(list(reversed(copy.deepcopy(expected))), expected))

    def test_one_double_ulp_change_and_signed_zero_are_rejected(self):
        for value, changed in ((0.125, math.nextafter(0.125, math.inf)), (0.0, -0.0)):
            with self.subTest(value=value):
                expected = source_row()
                expected["gmm_retail_ratio"] = value
                actual = {**expected, "gmm_retail_ratio": changed}
                with self.assertRaises(RuntimeError):
                    self.compare([actual], [expected])

    def test_null_values_are_preserved_and_nonfinite_values_are_rejected(self):
        expected = source_row()
        expected["mean_retail_entropy"] = None
        self.assertEqual(15, self.compare([dict(expected)], [expected]))
        with self.assertRaises(RuntimeError):
            self.compare([{**expected, "mean_retail_entropy": 0.0}], [expected])
        for value in (float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                self.compare([{**expected, "gmm_retail_ratio": value}], [{**expected, "gmm_retail_ratio": value}])

    def test_long_values_above_double_precision_remain_exact(self):
        expected = source_row()
        expected["q1_count"] = 9007199254740993
        self.assertEqual(15, self.compare([dict(expected)], [expected]))
        with self.assertRaises(RuntimeError):
            self.compare([{**expected, "q1_count": 9007199254740992}], [expected])

    def test_missing_additional_duplicate_and_changed_full_timestamp_keys_are_rejected(self):
        expected = [source_row()]
        for actual in ([], [source_row(), source_row(2)], [source_row(), source_row()],
                       [{**source_row(), "ts": "2026-09-17T00:00:00.000001Z"}]):
            with self.subTest(actual=actual), self.assertRaises(RuntimeError):
                self.compare(actual, expected)

    def test_null_blank_and_missing_identity_keys_are_rejected_on_both_sides(self):
        for field, value in (("ts", None), ("ts", ""), ("ts", "  "),
                             ("symbol", None), ("symbol", ""), ("symbol", "  ")):
            with self.subTest(field=field, value=value):
                row = {**source_row(), field: value}
                with self.assertRaises(RuntimeError):
                    self.compare([dict(row)], [row])
                with self.assertRaises(RuntimeError):
                    self.compare([source_row()], [row])
                with self.assertRaises(RuntimeError):
                    self.compare([row], [source_row()])
        for field in ("ts", "symbol"):
            row = source_row()
            del row[field]
            with self.subTest(missing=field), self.assertRaises(RuntimeError):
                self.compare([dict(row)], [row])


class MutationAndFailureTests(unittest.TestCase):
    def setUp(self):
        self.target = MagicMock(name="private_attestation")
        self.connect = MagicMock(name="private_pg_connect")
        self.cursor = self.connect.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value
        self.execute = MagicMock(name="execute_values")
        self.qwp = MagicMock(name="qwp")
        self.wait = MagicMock(name="wal_wait")
        self.saved = []
        self.result = {"fixture_submissions": []}
        self.save = MagicMock(side_effect=lambda output, result: self.saved.append(copy.deepcopy(result)))
        for replacement in (patch.object(sut, "PRIVATE_TARGET", self.target),
                patch.object(sut.psycopg2, "connect", self.connect), patch.object(sut, "execute_values", self.execute),
                patch.object(sut, "qwp", self.qwp), patch.object(sut, "save", self.save),
                patch.object(sut.common, "wait_until", self.wait)):
            replacement.start()
            self.addCleanup(replacement.stop)

    def insert(self, rows, table=None, fields=None):
        return sut.insert_fixture(table or sut.SOURCE, fields or sut.audit.SOURCE_FIELDS,
                                  rows, TOOLS / "unused-d098-test-output.json", self.result, "mock_phase")

    def test_unknown_owner_table_and_fields_are_rejected_before_any_io(self):
        with self.assertRaises(RuntimeError):
            sut.ensure_table("unrelated_table", {"ts": "TIMESTAMP"}, "ts", "DAY", ("ts",))
        with self.assertRaises(RuntimeError):
            self.insert([source_row()], table="unrelated_table")
        with self.assertRaises(RuntimeError):
            self.insert([source_row()], fields=("ts", "symbol"))
        self.qwp.assert_not_called()
        self.connect.assert_not_called()
        self.target.verify.assert_not_called()
        self.save.assert_not_called()

    def test_empty_and_overbudget_source_batches_are_rejected_before_connection(self):
        overbudget = MagicMock()
        overbudget.__len__.return_value = 20001
        overbudget.__bool__.return_value = True
        for rows in ([], overbudget):
            with self.subTest(rows=rows), self.assertRaises(RuntimeError):
                self.insert(rows)
        self.connect.assert_not_called()
        self.target.verify.assert_not_called()
        self.save.assert_not_called()

    def test_second_batch_unknown_stops_later_batches_and_preserves_exact_attempt_counts(self):
        rows = [source_row(index) for index in range(1001)]
        self.execute.side_effect = [None, TimeoutError("ambiguous second INSERT response")]
        with self.assertRaises(TimeoutError):
            self.insert(rows)
        self.assertEqual(2, self.execute.call_count)
        self.assertEqual(1000, sum(len(call.args[2]) for call in self.execute.call_args_list))
        phase = self.result["fixture_submissions"][0]
        self.assertEqual(1001, phase["submitted_rows"])  # Legacy planned payload, never confirmed rows.
        self.assertEqual(1001, phase["planned_rows"])
        self.assertEqual(1000, phase["attempted_rows"])
        self.assertEqual(500, phase["acknowledged_rows"])
        self.assertEqual("UNKNOWN", phase["ack"])
        self.assertEqual(["ACKNOWLEDGED", "UNKNOWN"], [batch["ack"] for batch in phase["batches"]])
        self.assertEqual([0, 500], [batch["offset"] for batch in phase["batches"]])
        self.assertEqual([500, 0], [batch["acknowledged_rows"] for batch in phase["batches"]])
        self.assertFalse(phase["automatic_retry"])
        self.assertTrue(all(not batch["automatic_retry"] for batch in phase["batches"]))
        self.assertEqual(4, self.save.call_count)
        self.assertEqual(phase, self.saved[-1]["fixture_submissions"][0])
        self.assertEqual(3, self.target.verify.call_count)  # Whole pass + two attempted batches.
        self.connect.assert_called_once()
        self.wait.assert_not_called()

    def test_attestation_change_between_batches_prevents_second_insert(self):
        self.target.verify.side_effect = [None, None, RuntimeError("process changed")]
        with self.assertRaises(RuntimeError):
            self.insert([source_row(index) for index in range(501)])
        self.execute.assert_called_once()
        phase = self.result["fixture_submissions"][0]
        self.assertEqual("UNKNOWN", phase["ack"])
        self.assertEqual(501, phase["planned_rows"])
        self.assertEqual(500, phase["attempted_rows"])
        self.assertEqual(500, phase["acknowledged_rows"])
        self.assertEqual(1, len(phase["batches"]))
        self.assertEqual(phase, self.saved[-1]["fixture_submissions"][0])
        self.wait.assert_not_called()

    def test_success_has_bounded_owner_field_order_and_unknown_then_ack_marker(self):
        rows = [source_row(index) for index in range(501)]
        self.insert(rows)
        self.assertEqual(2, self.execute.call_count)
        self.assertEqual([500, 1], [len(call.args[2]) for call in self.execute.call_args_list])
        for call in self.execute.call_args_list:
            self.assertIn(f"INSERT INTO {sut.SOURCE} ({','.join(sut.audit.SOURCE_FIELDS)}) VALUES %s", call.args[1])
            self.assertEqual(500, call.kwargs["page_size"])
        phase = self.result["fixture_submissions"][0]
        self.assertEqual(["UNKNOWN"] * 5 + ["ACKNOWLEDGED"],
                         [record["fixture_submissions"][0]["ack"] for record in self.saved])
        self.assertEqual((501, 501, 501), (phase["planned_rows"], phase["attempted_rows"], phase["acknowledged_rows"]))
        self.assertEqual(["ACKNOWLEDGED", "ACKNOWLEDGED"], [batch["ack"] for batch in phase["batches"]])
        self.wait.assert_called_once()
        self.assertEqual(3, self.target.verify.call_count)

    def test_each_unknown_batch_intent_is_saved_before_the_single_driver_call(self):
        def execute(cursor, sql, rows, **kwargs):
            persisted = self.saved[-1]["fixture_submissions"][0]
            self.assertEqual("UNKNOWN", persisted["batches"][-1]["ack"])
            self.assertEqual(len(rows), persisted["batches"][-1]["attempted_rows"])
            self.assertEqual(0, persisted["batches"][-1]["acknowledged_rows"])
            self.assertEqual(self.execute.call_count, len(persisted["batches"]))
        self.execute.side_effect = execute
        self.insert([source_row(index) for index in range(501)])
        self.assertEqual(2, self.execute.call_count)

    def test_unpersisted_batch_intent_prevents_driver_call(self):
        def save(output, result):
            if result["fixture_submissions"][0]["batches"]:
                raise OSError("durable intent unavailable")
            self.saved.append(copy.deepcopy(result))
        self.save.side_effect = save
        with self.assertRaises(OSError):
            self.insert([source_row()])
        self.execute.assert_not_called()
        self.assertEqual([], self.saved[-1]["fixture_submissions"][0]["batches"])
        self.wait.assert_not_called()

    def test_unknown_connection_exit_keeps_phase_unknown_without_retry(self):
        self.connect.return_value.__exit__.side_effect = TimeoutError("ambiguous connection completion")
        with self.assertRaises(TimeoutError):
            self.insert([source_row()])
        self.execute.assert_called_once()
        phase = self.result["fixture_submissions"][0]
        self.assertEqual("UNKNOWN", phase["ack"])
        self.assertEqual("ACKNOWLEDGED", phase["batches"][0]["ack"])
        self.assertEqual(phase, self.saved[-1]["fixture_submissions"][0])
        self.connect.assert_called_once()
        self.wait.assert_not_called()

    def test_incomplete_keys_and_nonfinite_values_are_rejected_before_any_write(self):
        for field, value in (("ts", None), ("symbol", ""), ("symbol", "  "),
                             ("gmm_retail_ratio", float("nan"))):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                self.insert([{**source_row(), field: value}])
        self.connect.assert_not_called()
        self.target.verify.assert_not_called()
        self.save.assert_not_called()
        self.execute.assert_not_called()


class DurableSaveTests(unittest.TestCase):
    def test_json_replace_is_after_file_flush_and_fsync(self):
        output = MagicMock(spec=Path)
        temporary = MagicMock(spec=Path)
        output.name = "mock-output.json"
        output.with_name.return_value = temporary
        stream = temporary.open.return_value.__enter__.return_value
        events = []
        stream.write.side_effect = lambda payload: events.append("write")
        stream.flush.side_effect = lambda: events.append("flush")
        with patch.object(sut.os, "fsync", side_effect=lambda fd: events.append("fsync")) as fsync, \
                patch.object(sut.os, "replace", side_effect=lambda source, target: events.append("replace")) as replace:
            sut.save(output, {"ack": "UNKNOWN"})
        self.assertEqual(["write", "flush", "fsync", "replace"], events)
        fsync.assert_called_once_with(stream.fileno.return_value)
        replace.assert_called_once_with(temporary, output)
        temporary.unlink.assert_called_once_with(missing_ok=True)

    def test_failed_fsync_never_replaces_existing_artifact(self):
        output = MagicMock(spec=Path)
        temporary = output.with_name.return_value
        with patch.object(sut.os, "fsync", side_effect=OSError("disk unavailable")), \
                patch.object(sut.os, "replace") as replace:
            with self.assertRaises(OSError):
                sut.save(output, {"ack": "UNKNOWN"})
        replace.assert_not_called()
        temporary.unlink.assert_called_once_with(missing_ok=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
