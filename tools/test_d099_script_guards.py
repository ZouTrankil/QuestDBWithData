"""D099 pure guard checks: all DB, HTTP and process attestation are mocked."""
from __future__ import annotations

import copy
import importlib
import io
import json
import math
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch
from urllib.parse import parse_qs, urlparse

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
sut = importlib.import_module("audit_d099_retail_alias")


def daily_row(day="2026-09-17"):
    return {field: (f"{day}T00:00:00.000000Z" if kind == "TIMESTAMP" else 1 if kind == "LONG" else 0.125)
            for field, kind in sut.TYPES.items()}


def snapshot(alias=None):
    result = {"tables": {}, "wal": {}, "alias": alias}
    for table, identity, rows in ((sut.SOURCE, 9, sut.EXPECTED_ROWS), (sut.MV, 11, 3)):
        result["tables"][table] = {"id": identity, "directoryName": f"{table}~{identity}", "table_txn": 36,
            "table_row_count": rows, "table_min_timestamp": "2026-09-17T00:00:00.000000Z",
            "table_max_timestamp": "2026-09-21T00:00:00.000000Z", "partitionBy": "DAY" if table == sut.SOURCE else "MONTH",
            "walEnabled": True, "dedup": table == sut.SOURCE, "designatedTimestamp": "ts" if table == sut.SOURCE else "trade_date",
            "table_suspended": False, "wal_pending_row_count": 0}
        result["wal"][table] = {"sequencerTxn": 36, "writerTxn": 36, "bufferedTxnSize": 0, "suspended": False}
    result["mv"] = {"view_name": sut.MV, "view_status": "valid", "invalidation_reason": None,
        "base_table_name": sut.SOURCE, "view_table_dir_name": f"{sut.MV}~11", "refresh_base_table_txn": 36,
        "base_table_txn": 36, "refresh_type": "timer", "timer_interval": 1, "timer_interval_unit": "MINUTE",
        "view_sql": sut.parent.OWNER_SQL, "view_sql_sha256": sut.sha(sut.parent.OWNER_SQL), "definition_matches_python_exact": True}
    return result


def valid_alias():
    return {"view_name": sut.ALIAS, "view_sql": sut.ALIAS_SELECT, "view_status": "valid", "invalidation_reason": None,
            "view_table_dir_name": f"{sut.ALIAS}~12"}


class SelectAndMetadataTests(unittest.TestCase):
    def test_formal_and_private_only_accept_single_multiline_select(self):
        sql = "\nSELECT\ncount() FROM l2_daily_features"
        with patch.object(sut.parent, "query", return_value=[]) as formal, patch.object(sut.fixture, "qwp", return_value=[]) as private:
            self.assertEqual([], sut.query(sql, False))
            self.assertEqual([], sut.query(sql, True))
            formal.assert_called_once_with(sql)
            private.assert_called_once_with(sql)

    def test_empty_nonselect_or_semicolon_is_rejected_before_either_target(self):
        with patch.object(sut.parent, "query") as formal, patch.object(sut.fixture, "qwp") as private:
            for isolated in (False, True):
                for sql in ("", " \n", "CREATE VIEW x AS(SELECT 1)", "REFRESH MATERIALIZED VIEW x FULL",
                            "INSERT INTO x VALUES(1)", "SELECT 1;", "SELECT 1; DROP TABLE x"):
                    with self.subTest(isolated=isolated, sql=sql), self.assertRaises(RuntimeError):
                        sut.query(sql, isolated)
            formal.assert_not_called()
            private.assert_not_called()

    def test_state_uses_actual_view_table_dir_name_and_keeps_alias_identity(self):
        expected = snapshot(valid_alias())
        answers = [[expected["tables"][sut.SOURCE]], [expected["wal"][sut.SOURCE]],
                   [expected["tables"][sut.MV]], [expected["wal"][sut.MV]], [expected["mv"]], [expected["alias"]]]
        with patch.object(sut, "query", side_effect=answers):
            self.assertEqual(expected, sut.state(True))

    def test_ambiguous_alias_rows_are_rejected(self):
        expected = snapshot(valid_alias())
        answers = [[expected["tables"][sut.SOURCE]], [expected["wal"][sut.SOURCE]],
                   [expected["tables"][sut.MV]], [expected["wal"][sut.MV]], [expected["mv"]], [valid_alias(), valid_alias()]]
        with patch.object(sut, "query", side_effect=answers), self.assertRaises(RuntimeError):
            sut.state(True)

    def test_invalid_lagging_wal_unsettled_or_wrong_definition_is_never_ready(self):
        changes = (("mv", "view_status", "invalid"), ("mv", "invalidation_reason", "invalid parent"),
                   ("mv", "refresh_base_table_txn", 35), ("mv", "base_table_txn", 35),
                   ("mv", "definition_matches_python_exact", False), ("mv", "base_table_name", "wrong_source"),
                   ("mv", "timer_interval", 2))
        for section, field, value in changes:
            observed = snapshot()
            observed[section][field] = value
            with self.subTest(field=field):
                self.assertFalse(sut.ready(observed))
        for table in (sut.SOURCE, sut.MV):
            for section, field, value in (("tables", "table_suspended", True), ("tables", "wal_pending_row_count", 1),
                                         ("wal", "suspended", True), ("wal", "bufferedTxnSize", 1), ("wal", "writerTxn", 35)):
                observed = snapshot()
                observed[section][table][field] = value
                with self.subTest(table=table, field=field):
                    self.assertFalse(sut.ready(observed))
        self.assertTrue(sut.ready(snapshot()))

    def test_schema_requires_all_thirteen_fields_in_the_frozen_order(self):
        columns = [{"column": field, "type": kind} for field, kind in sut.TYPES.items()]
        with patch.object(sut, "query", return_value=columns):
            self.assertEqual(sut.TYPES, sut.schema(sut.ALIAS, True))
        for invalid in (columns[:-1], list(reversed(columns)), [{**row, "type": "INT"} if row["type"] == "LONG" else row for row in columns]):
            with patch.object(sut, "query", return_value=invalid), self.assertRaises(RuntimeError):
                sut.schema(sut.ALIAS, True)


class TypedComparisonTests(unittest.TestCase):
    def test_all_thirteen_fields_across_three_dates_are_compared(self):
        rows = [daily_row(day) for day in sut.EXPECTED_DAYS]
        result = sut.comparison(list(reversed(rows)), rows, exact_double=True)
        self.assertTrue(result["passed"])
        self.assertEqual(39, result["field_comparisons"])
        self.assertEqual([], result["mismatches"])

    def test_alias_mv_one_ulp_drift_is_rejected_but_owner_aggregation_tolerance_is_explicit(self):
        original = daily_row()
        changed = {**original, "avg_retail_ratio": math.nextafter(original["avg_retail_ratio"], math.inf)}
        self.assertFalse(sut.comparison([changed], [original], exact_double=True)["passed"])
        self.assertTrue(sut.comparison([changed], [original])["passed"])
        changed["avg_retail_ratio"] += 1e-4
        self.assertFalse(sut.comparison([changed], [original])["passed"])

    def test_signed_inflow_null_and_large_long_keep_their_semantics(self):
        original = daily_row()
        original.update(total_retail_net_inflow_yi=-840.5, total_main_net_yi=-162.5, avg_mfi_score=None,
                        total_q1=9007199254740993)
        self.assertTrue(sut.comparison([dict(original)], [original])["passed"])
        for field, value in (("total_retail_net_inflow_yi", 840.5), ("avg_mfi_score", 0.0), ("total_q1", 9007199254740992)):
            with self.subTest(field=field):
                self.assertFalse(sut.comparison([{**original, field: value}], [original])["passed"])

    def test_missing_extra_duplicate_empty_and_incomplete_daily_keys_are_not_success(self):
        expected = [daily_row()]
        candidates = ([], [daily_row(), daily_row("2026-09-18")], [daily_row(), daily_row()],
                      [{**daily_row(), "trade_date": None}], [{**daily_row(), "trade_date": "2026-09-17"}],
                      [{**daily_row(), "trade_date": "2026-09-17T00:00:00.000001Z"}])
        for rows in candidates:
            with self.subTest(rows=rows):
                self.assertFalse(sut.comparison(rows, expected)["passed"])
        self.assertFalse(sut.comparison([], [])["passed"])

    def test_nonfinite_doubles_and_float_or_bool_longs_are_rejected(self):
        original = daily_row()
        for field, value in (("avg_retail_ratio", math.inf), ("avg_retail_ratio", math.nan),
                             ("total_q1", 1.0), ("total_q1", True)):
            with self.subTest(field=field, value=value):
                self.assertFalse(sut.comparison([{**original, field: value}], [original])["passed"])


    def test_negative_nullable_counts_are_rejected_without_clamping(self):
        original = daily_row()
        for field, kind in sut.TYPES.items():
            if kind == "LONG":
                with self.subTest(field=field):
                    self.assertFalse(sut.comparison([{**original, field: -1}], [original])["passed"])


class MissingAliasMutationTests(unittest.TestCase):
    def setUp(self):
        self.result = {"task_id": "D099", "formal": {"target_validated": False}, "isolated": {}}
        self.target = MagicMock()
        self.target.identity = {"pid": sut.EXPECTED_PID, "data_root": str(sut.ROOT.resolve()), "http_port": 19010, "pg_port": 18822}
        self.constructor = self.start(patch.object(sut.fixture, "PrivateTarget", return_value=self.target))
        self.observed = snapshot()
        self.state = self.start(patch.object(sut, "state", side_effect=[self.observed, copy.deepcopy(self.observed), snapshot(valid_alias())]))
        self.start(patch.object(sut, "schema", return_value=sut.TYPES))
        self.start(patch.object(sut, "source_contract", return_value={}))
        self.saved = []
        self.save = self.start(patch.object(sut.fixture, "save", side_effect=lambda output, result: self.saved.append(copy.deepcopy(result))))
        self.http = self.start(patch.object(sut.fixture, "urlopen", return_value=io.BytesIO(b'{"ddl":"OK"}')))
        original = sut.fixture.PRIVATE_TARGET
        self.addCleanup(setattr, sut.fixture, "PRIVATE_TARGET", original)

    def start(self, replacement):
        mock = replacement.start()
        self.addCleanup(replacement.stop)
        return mock

    def ensure(self, root=None):
        return sut.ensure_private_alias(root or sut.ROOT, sut.EXPECTED_PID, Path("unused-d099-mock.json"), self.result)

    def test_missing_only_create_is_fixed_target_and_has_durable_unknown_before_response(self):
        def execute(request, **kwargs):
            self.assertEqual("UNKNOWN", self.saved[-1]["isolated"]["alias_submission"]["ack"])
            self.assertEqual("D099", self.saved[-1]["task_id"])
            self.assertFalse(self.saved[-1]["formal"]["target_validated"])
            self.assertFalse(self.saved[-1]["isolated"]["alias_submission"]["automatic_retry"])
            self.assertTrue(request.full_url.startswith("http://127.0.0.1:19010/exec?"))
            self.assertEqual(sut.ALIAS_DDL, parse_qs(urlparse(request.full_url).query)["query"][0])
            return io.BytesIO(b'{"ddl":"OK"}')
        self.http.side_effect = execute
        self.ensure()
        self.constructor.assert_called_once_with(sut.ROOT, sut.EXPECTED_PID)
        self.target.verify.assert_called_once()
        self.http.assert_called_once()
        self.assertEqual(["UNKNOWN", "ACKNOWLEDGED"], [document["isolated"]["alias_submission"]["ack"] for document in self.saved])
        self.assertTrue(self.result["isolated"]["alias_created"])

    def test_matching_existing_alias_is_reused_without_ddl_or_submission(self):
        self.state.side_effect = None
        self.state.return_value = snapshot(valid_alias())
        self.ensure()
        self.assertFalse(self.result["isolated"]["alias_created"])
        self.assertNotIn("alias_submission", self.result["isolated"])
        self.http.assert_not_called()
        self.save.assert_not_called()

    def test_existing_wrong_or_invalid_alias_is_never_replaced(self):
        for field, value in (("view_sql", f"SELECT * FROM {sut.SOURCE}"), ("view_status", "invalid"),
                             ("invalidation_reason", "alias invalid")):
            observed = snapshot({**valid_alias(), field: value})
            self.state.side_effect = None
            self.state.return_value = observed
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                self.ensure()
        self.http.assert_not_called()
        self.save.assert_not_called()

    def test_wrong_root_or_unready_parent_is_rejected_before_mutation(self):
        with self.assertRaises(RuntimeError):
            self.ensure(sut.fixture.common.REPO_ROOT)
        self.constructor.assert_not_called()
        observed = snapshot()
        observed["mv"]["view_status"] = "invalid"
        self.state.side_effect = None
        self.state.return_value = observed
        with self.assertRaises(RuntimeError):
            self.ensure()
        self.http.assert_not_called()
        self.save.assert_not_called()

    def test_process_attestation_failure_prevents_the_ddl_http_request(self):
        self.target.verify.side_effect = RuntimeError("private process changed")
        with self.assertRaises(RuntimeError):
            self.ensure()
        self.http.assert_not_called()
        self.assertEqual("UNKNOWN", self.saved[-1]["isolated"]["alias_submission"]["ack"])

    def test_unknown_http_response_is_preserved_without_retry_or_parent_writes(self):
        self.http.side_effect = TimeoutError("ambiguous alias response")
        with self.assertRaises(TimeoutError):
            self.ensure()
        self.http.assert_called_once()
        self.assertEqual(1, self.save.call_count)
        self.assertEqual("UNKNOWN", self.result["isolated"]["alias_submission"]["ack"])
        self.assertFalse(self.result["isolated"]["alias_submission"]["automatic_retry"])

    def test_failed_intent_save_prevents_ddl(self):
        self.save.side_effect = OSError("intent unavailable")
        with self.assertRaises(OSError):
            self.ensure()
        self.http.assert_not_called()

    def test_protected_metadata_drift_before_or_after_ddl_is_rejected(self):
        changed = copy.deepcopy(self.observed)
        changed["tables"][sut.SOURCE]["table_txn"] += 1
        self.state.side_effect = [self.observed, changed]
        with self.assertRaises(RuntimeError):
            self.ensure()
        self.http.assert_not_called()
        changed["alias"] = valid_alias()
        self.state.side_effect = [self.observed, copy.deepcopy(self.observed), changed]
        with self.assertRaises(RuntimeError):
            self.ensure()
        self.http.assert_called_once()
        self.assertEqual("ACKNOWLEDGED", self.result["isolated"]["alias_submission"]["ack"])
        self.assertNotIn("alias_created", self.result["isolated"])


class EvidencePreservationTests(unittest.TestCase):
    def test_unknown_previous_ddl_is_not_overwritten_or_automatically_retried(self):
        output = MagicMock(spec=Path)
        output.exists.return_value = True
        previous = {"task_id": "D099", "status": "FAILED", "isolated": {"alias_submission": {"ack": "UNKNOWN"}}}
        output.read_text.return_value = json.dumps(previous)
        with patch.object(sut.shutil, "copyfile") as archive, patch.object(sut.fixture, "save") as save, \
                patch.object(sut, "query") as query, self.assertRaises(RuntimeError):
            sut.preserve_previous_output(output)
        archive.assert_not_called()
        save.assert_not_called()
        query.assert_not_called()
        output.write_text.assert_not_called()

    def test_existing_known_result_is_archived_before_an_explicit_new_audit(self):
        output = MagicMock(spec=Path)
        output.name, output.stem, output.suffix = "audit.json", "audit", ".json"
        output.exists.return_value = True
        output.read_text.return_value = json.dumps({"task_id": "D099", "status": "FAILED", "error": "schema drift"})
        with patch.object(sut.shutil, "copyfile") as archive:
            destination = sut.preserve_previous_output(output)
        archive.assert_called_once_with(output, destination)
        self.assertIn("audit.previous-", output.with_name.call_args.args[0])
        output.write_text.assert_not_called()

    def test_first_output_needs_no_history_read_or_archive(self):
        output = MagicMock(spec=Path)
        output.exists.return_value = False
        with patch.object(sut.shutil, "copyfile") as archive:
            self.assertIsNone(sut.preserve_previous_output(output))
        output.read_text.assert_not_called()
        archive.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
