"""Pure regressions for D095 scripts; no database or network access."""
import copy
import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


repair = load("d095_repair", "repair_d095_mv_after_approval.py")
isolated = load("d095_isolated", "accept_d095_mv_isolated.py")


def stable_snapshot(source_txn=14, mv_txn=3):
    return {"source": {"table_name": "stk_factor", "table_txn": source_txn, "table_row_count": 2},
            "mv": {"table_name": repair.MV, "table_txn": mv_txn, "table_row_count": 1},
            "state": {"view_status": "valid", "base_table_txn": source_txn,
                      "refresh_base_table_txn": source_txn, "view_table_dir_name": "mv~123"}}


def aggregate():
    return [{"trade_date": "2026-09-17T00:00:00.000000Z", "stock_count": 2,
             "up_count": 1, "down_count": 1, "flat_count": 0,
             "avg_pct_change": None, "total_amount_yi": 1.0}]


class FormalParityTests(unittest.TestCase):
    def verify(self, snapshots, attempts=1):
        with patch.object(repair, "snapshot", side_effect=snapshots), \
                patch.object(repair, "query", side_effect=lambda sql, **kwargs: aggregate()) as query, \
                patch.object(repair.time, "sleep"):
            result = repair.verify_stable_parity("SELECT source", "SELECT mv", attempts=attempts)
        self.assertTrue(all(call.args[0].startswith("SELECT ") for call in query.call_args_list))
        return result, query.call_count

    def test_stable_source_and_mv_verify_all_values(self):
        result, queries = self.verify([stable_snapshot()] * 3)
        self.assertTrue(result["versions_stable"])
        self.assertTrue(result["full_parity"]["passed"])
        self.assertEqual(6, result["full_parity"]["compared_values"])
        self.assertEqual(2, queries)

    def test_source_write_after_both_reads_cannot_verify(self):
        result, _ = self.verify([stable_snapshot(), stable_snapshot(), stable_snapshot(source_txn=15)])
        self.assertFalse(result["versions_stable"])
        self.assertNotIn("full_parity", result)

    def test_range_refresh_without_checkpoint_change_cannot_verify(self):
        result, _ = self.verify([stable_snapshot(), stable_snapshot(mv_txn=4), stable_snapshot(mv_txn=4)])
        self.assertFalse(result["versions_stable"])

    def test_instability_retries_only_reads_then_accepts_a_stable_version(self):
        changed = stable_snapshot(source_txn=15, mv_txn=4)
        result, queries = self.verify([stable_snapshot(), changed, changed, changed, changed, changed], 2)
        self.assertTrue(result["versions_stable"])
        self.assertEqual(2, len(result["attempts"]))
        self.assertEqual(4, queries)

    def test_new_source_txn_with_old_checkpoint_is_not_synchronized(self):
        observed = stable_snapshot()
        observed["source"]["table_txn"] = 15
        self.assertFalse(repair.synchronized(observed))

    def test_snapshot_fails_if_state_changes_during_capture(self):
        before, after = stable_snapshot(), stable_snapshot(mv_txn=4)
        after["state"]["last_refresh_finish_timestamp"] = "later"
        with patch.object(repair, "state", side_effect=[before["state"], after["state"]]), \
                patch.object(repair, "table_identity", side_effect=[before["source"], before["mv"]]):
            with self.assertRaisesRegex(RuntimeError, "boundary"):
                repair.snapshot()

    def test_ambiguous_full_submission_remains_in_doubt_and_is_not_retried(self):
        expected = json.loads((ROOT / "artifacts/java-migration/D095/commands/"
                              "formal-readonly-audit-20260930.json").read_text(encoding="utf-8"))
        tables = {row["table_name"]: row for row in expected["tables"]}
        submissions = []

        def query(sql, **kwargs):
            if sql.startswith("REFRESH "):
                submissions.append(sql)
                raise TimeoutError("response lost after submission")
            return [{"view_sql": f"SELECT * FROM {repair.MV}"}]

        with tempfile.TemporaryDirectory() as temp:
            result_file = Path(temp) / "result.json"
            audit_file = Path(temp) / "audit.json"
            audit_file.write_text(json.dumps(expected), encoding="utf-8")
            with patch.object(repair, "RESULT", result_file), patch.object(repair, "AUDIT", audit_file), \
                    patch.object(repair, "state", return_value=expected["mv_state"]), \
                    patch.object(repair, "table_identity", side_effect=lambda name: tables[name]), \
                    patch.object(repair, "query", side_effect=query), \
                    patch.dict(os.environ, {"APP_QUESTDB_HOST": "127.0.0.1"}), \
                    patch.object(sys, "argv", ["repair", "--execute"]):
                with self.assertRaises(TimeoutError):
                    repair.main()
                result = json.loads(result_file.read_text(encoding="utf-8"))
                self.assertEqual("in_doubt", result["status"])
                self.assertEqual("unknown", result["submitted"])
                with self.assertRaisesRegex(RuntimeError, "Prior FULL"):
                    repair.main()
            self.assertEqual([f"REFRESH MATERIALIZED VIEW {repair.MV} FULL"], submissions)


class IsolatedGuardsTests(unittest.TestCase):
    def test_duplicate_full_timestamp_bucket_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Duplicate"):
            isolated.compare(aggregate() * 2, aggregate())

    def test_same_date_with_different_timestamp_is_not_equal(self):
        actual = copy.deepcopy(aggregate())
        actual[0]["trade_date"] = "2026-09-17T12:00:00.000000Z"
        with self.assertRaisesRegex(RuntimeError, "keys"):
            isolated.compare(actual, aggregate())

    def test_all_physical_source_fields_are_compared_by_full_key(self):
        expected = [{"trade_date": "2026-09-17T00:00:00.000000Z", "ts_code": "000001.SZ",
                     "pct_change": 2.5, "amount": None}]
        fields = ("trade_date", "ts_code", "pct_change", "amount")
        self.assertEqual(4, isolated.compare_fields(expected, expected, fields[:2], fields))
        altered = copy.deepcopy(expected)
        altered[0]["pct_change"] = 3.5
        with self.assertRaisesRegex(RuntimeError, "pct_change"):
            isolated.compare_fields(altered, expected, fields[:2], fields)

    def records(self, root):
        return [{"port": port, "pid": 123, "address": "127.0.0.1", "name": "questdb.exe",
                 "command": f'questdb.exe start -f -d "{root}"'} for port in (19000, 18812)]

    def test_target_requires_same_process_and_exact_private_root(self):
        root = (ROOT / "var/d095-isolated-questdb").resolve()
        records = self.records(root)
        with patch.object(isolated, "split_windows_command_line", return_value=["questdb.exe", "-d", str(root)]):
            self.assertEqual(123, isolated.validate_listener_records(records, root)["pid"])
            records[1]["pid"] = 124
            with self.assertRaisesRegex(RuntimeError, "same QuestDB process"):
                isolated.validate_listener_records(records, root)

    def test_formal_data_root_cannot_pass_as_private_listener(self):
        root = (ROOT / "var/d095-isolated-questdb").resolve()
        with patch.object(isolated, "split_windows_command_line", return_value=["questdb.exe", "-d", "D:/tool/questdb/db/data"]):
            with self.assertRaisesRegex(RuntimeError, "different data root"):
                isolated.validate_listener_records(self.records(root), root)

    def test_private_listener_cannot_bind_to_all_addresses(self):
        root = (ROOT / "var/d095-isolated-questdb").resolve()
        records = self.records(root)
        records[0]["address"] = "0.0.0.0"
        with self.assertRaisesRegex(RuntimeError, "loopback"):
            isolated.validate_listener_records(records, root)

    def test_multiline_python_owner_select_passes_read_only_gate(self):
        class Response:
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def read(self):
                return '{"columns": [], "dataset": []}'
        with patch.dict(os.environ, {"APP_QUESTDB_HOST": "127.0.0.1",
                                    "APP_QUESTDB_USERNAME": "test", "APP_QUESTDB_PASSWORD": "test"}), \
                patch.object(isolated, "urlopen", return_value=Response()) as network:
            self.assertEqual([], isolated.qwp("SELECT\n 1"))
        network.assert_called_once()

    def test_formal_mutation_is_rejected_before_network_access(self):
        with patch.dict(os.environ, {"APP_QUESTDB_HOST": "127.0.0.1"}), \
                patch.object(isolated, "urlopen") as network:
            with self.assertRaisesRegex(RuntimeError, "SELECT"):
                isolated.qwp("REFRESH MATERIALIZED VIEW anything FULL")
        network.assert_not_called()


if __name__ == "__main__":
    unittest.main()
