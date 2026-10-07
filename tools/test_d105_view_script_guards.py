"""Pure D105 guards: mocked database/native/HTTP and filesystem journals only."""
from __future__ import annotations

import copy
import json
import math
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_d105_macro_core_view as audit
import prepare_d105_macro_core_view_isolated as sut


def row(month="2026-06-01T00:00:00.000000Z"):
    return dict(zip(audit.FIELDS, (month, 1.0, 4.1, 50.3, None, 8.0, 462.06, 33671.0, 0.07395872071401999)))


def view(isolated=False):
    return {"view_name": audit.PRIVATE_VIEW if isolated else audit.VIEW, "view_sql": "\n SELECT * FROM " + (audit.PRIVATE_BASE if isolated else audit.BASE) + "\n",
            "view_status": "valid", "invalidation_reason": None, "view_table_dir_name": (audit.PRIVATE_VIEW if isolated else audit.VIEW) + "~1806", "view_status_update_time": "2026-09-30T01:27:52.535072Z"}


def schema():
    return [{"column": field, "type": audit.TYPES[field], "designated": field == "month", "upsertKey": False} for field in audit.FIELDS]


def gate():
    return {"protocol_version": 1, "task_id": "D105", "decision": "accepted_for_private_view_create_once", "new_DDL": 1, "new_DML": 0, "new_ILP": 0, "base_writes": 0, "formal_writes": 0, "automatic_retry": False}


class SelectAndValuesTests(unittest.TestCase):
    def test_multiline_select_with_binds_is_allowed(self):
        audit.common.single_select("\n SELECT month,cpi_yoy FROM macro_core_monthly WHERE month>=%s\n")

    def test_empty_nonselect_and_semicolon_rejected(self):
        for sql in ("", " ", "CREATE VIEW x AS SELECT 1", "UPDATE x SET a=1", "SELECT 1;", "SELECT 1; DELETE FROM x"):
            with self.subTest(sql=sql), self.assertRaises(RuntimeError):
                audit.common.single_select(sql)

    def test_only_four_fixed_read_objects_allowed(self):
        reader = MagicMock()
        for name in ("stk_factor", "cn_cpi", "macro_core_monthly; DROP TABLE x", "other_view"):
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                audit.finite_rows(reader, name)
        reader.records.assert_not_called()

    def test_finite_three_real_fields_and_exact_bound(self):
        reader = MagicMock(); values = [row()]; reader.records.return_value = values
        self.assertEqual(values, audit.finite_rows(reader, audit.VIEW))
        args, kwargs = reader.records.call_args
        self.assertIn("LIMIT 13", args[0]); self.assertEqual((audit.START, audit.STOP), args[1]); self.assertEqual(12, kwargs["cap"])

    def test_more_than_twelve_month_rows_rejected(self):
        reader = MagicMock(); reader.records.return_value = [row()] * 13
        with self.assertRaises(RuntimeError):
            audit.finite_rows(reader, audit.VIEW)

    def test_duplicate_month_keys_rejected(self):
        reader = MagicMock(); reader.records.return_value = [row(), row()]
        with self.assertRaises(RuntimeError):
            audit.finite_rows(reader, audit.VIEW)

    def test_wrong_column_order_or_missing_column_rejected(self):
        for value in (dict(reversed(list(row().items()))), {key: value for key, value in row().items() if key != "gdp_yoy"}):
            reader = MagicMock(); reader.records.return_value = [value]
            with self.assertRaises(RuntimeError):
                audit.finite_rows(reader, audit.VIEW)

    def test_month_must_be_first_day_utc_midnight(self):
        for month in ("2026-06-02T00:00:00Z", "2026-06-01T01:00:00Z", "2026-06-01T00:00:00+08:00", None):
            reader = MagicMock(); reader.records.return_value = [row(month)]
            with self.subTest(month=month), self.assertRaises((RuntimeError, TypeError)):
                audit.finite_rows(reader, audit.VIEW)

    def test_returned_month_outside_window_rejected(self):
        reader = MagicMock(); reader.records.return_value = [row("2026-09-01T00:00:00Z")]
        with self.assertRaises(RuntimeError):
            audit.finite_rows(reader, audit.VIEW)

    def test_current_future_or_overlong_windows_rejected(self):
        for start, stop in (("2026-10-01", "2026-11-01"), ("2027-01-01", "2027-02-01"), ("2025-01-01", "2026-03-01"), ("2026-06-02", "2026-09-01")):
            with self.subTest(start=start), self.assertRaises(RuntimeError):
                audit.finite_rows(MagicMock(), audit.VIEW, start, stop)

    def test_double_nan_infinity_bool_and_string_rejected(self):
        for wrong in (float("nan"), float("inf"), False, "1.0"):
            reader = MagicMock(); value = row(); value["cpi_yoy"] = wrong; reader.records.return_value = [value]
            with self.subTest(wrong=wrong), self.assertRaises(RuntimeError):
                audit.finite_rows(reader, audit.VIEW)

    def test_nullable_double_preserved_and_not_counted_as_bits(self):
        proof = audit.compare([row()], [row()], [row()])
        self.assertEqual(8, proof["nullable_double_slot_comparisons"])
        self.assertEqual(7, proof["nonnull_double_rawbit_comparisons"])
        self.assertEqual(1, proof["null_double_comparisons"])

    def test_single_ulp_difference_rejected_without_tolerance(self):
        changed = row(); changed["cpi_yoy"] = math.nextafter(changed["cpi_yoy"], math.inf)
        with self.assertRaises(RuntimeError):
            audit.compare([changed], [row()], [row()])

    def test_null_to_zero_difference_rejected(self):
        changed = row(); changed["gdp_yoy"] = 0.0
        with self.assertRaises(RuntimeError):
            audit.compare([changed], [row()], [row()])


class ViewMetadataTests(unittest.TestCase):
    def test_original_and_private_exact_identity_selects_allowed(self):
        audit.validate_view(view(), False, {})
        audit.validate_view(view(True), True, {})

    def test_existing_wrong_source_or_view_rejected(self):
        for key, wrong in (("view_name", "wrong"), ("view_sql", "SELECT * FROM other"), ("view_sql", "SELECT month FROM macro_core_monthly"), ("view_sql", "SELECT * FROM macro_core_monthly WHERE cpi_yoy>0")):
            value = view(); value[key] = wrong
            with self.subTest(key=key, wrong=wrong), self.assertRaises(RuntimeError):
                audit.validate_view(value, False, {})

    def test_invalid_view_or_reason_rejected(self):
        for key, wrong in (("view_status", "invalid"), ("invalidation_reason", "source dropped")):
            value = view(); value[key] = wrong
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                audit.validate_view(value, False, {})

    def test_missing_view_has_no_base_fallback(self):
        with self.assertRaises(RuntimeError):
            audit.validate_view(None, False, {})

    def test_blank_directory_and_history_rejected(self):
        for key in ("view_table_dir_name", "view_status_update_time"):
            for wrong in (None, "", " "):
                value = view(); value[key] = wrong
                with self.subTest(key=key, wrong=wrong), self.assertRaises(RuntimeError):
                    audit.validate_view(value, False, {})

    def test_exact_nine_native_schema_flags_allowed(self):
        reader = MagicMock(); reader.records.return_value = schema()
        self.assertEqual(schema(), audit.view_schema(reader, False))

    def test_schema_wrong_type_order_designated_or_upsert_rejected(self):
        variants = []
        value = schema(); value[1]["type"] = "LONG"; variants.append(value)
        variants.append(list(reversed(schema())))
        value = schema(); value[0]["designated"] = False; variants.append(value)
        value = schema(); value[1]["upsertKey"] = True; variants.append(value)
        value = schema(); value[1]["designated"] = 0; variants.append(value)
        for value in variants:
            reader = MagicMock(); reader.records.return_value = value
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                audit.view_schema(reader, True)

    def test_native_view_physical_directory_and_matview_rejected(self):
        good = {"id": 1806, "directoryName": view()["view_table_dir_name"], "partitionBy": "N/A", "walEnabled": True, "dedup": False, "matView": False, "designatedTimestamp": "month"}
        for key, wrong in (("directoryName", "wrong~1"), ("id", False), ("matView", True), ("partitionBy", "MONTH"), ("dedup", True)):
            value = dict(good); value[key] = wrong; reader = MagicMock(); reader.records.return_value = [value]
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                audit.view_physical(reader, False, view())

    def test_schema_drift_after_value_read_rejected(self):
        second = schema(); second[1]["upsertKey"] = True
        with patch.object(audit, "protected_state", return_value={}), patch.object(audit, "view_state", return_value=view()), patch.object(audit, "view_schema", side_effect=[schema(), second]), patch.object(audit, "view_physical", return_value={}), patch.object(audit, "read_windows", return_value=[{"actual_rows": [row()]}]), self.assertRaises(RuntimeError):
            audit.audit_target(MagicMock(), False, {})

    def test_seven_table_drift_after_value_read_rejected(self):
        with patch.object(audit, "protected_state", side_effect=[{"txn": 1}, {"txn": 2}]), patch.object(audit, "view_state", return_value=view()), patch.object(audit, "view_schema", return_value=schema()), patch.object(audit, "view_physical", return_value={}), patch.object(audit, "read_windows", return_value=[{"actual_rows": [row()]}]), self.assertRaises(RuntimeError):
            audit.audit_target(MagicMock(), False, {})


class TargetLedgerAndAdmissionTests(unittest.TestCase):
    def test_formal_pid_wrong_private_pid_and_root_rejected_before_native(self):
        for root, pid in ((audit.ROOT, 1), (audit.REPO, audit.PID), (audit.ROOT, True)):
            with patch.object(audit.base, "PrivateTarget") as target, self.subTest(root=root, pid=pid), self.assertRaises(RuntimeError):
                audit.private_target(root, pid, {})
            target.assert_not_called()

    def test_wrong_current_server_birth_rejected(self):
        identity = {"birth_utc": "2026-10-06T16:04:25Z"}
        with patch.object(audit.base, "PrivateTarget") as target, self.assertRaises(RuntimeError):
            target.return_value.verify.return_value = identity
            audit.private_target(audit.ROOT, audit.PID, {"results": {"private_target": identity}})

    def test_equivalent_six_and_seven_digit_os_birth_preserves_raw_identity(self):
        identity = {"birth_utc": "2026-10-06T16:04:24.4330550Z"}
        data = {"results": {"private_target": identity}, "java_final": {"native_attestations": [{"attestation_child_stopped": True, "records": []}]}}
        with patch.object(audit.base, "PrivateTarget") as target, patch.object(audit.base, "validate_listeners", return_value=identity):
            target.return_value.verify.return_value = identity
            self.assertIs(target.return_value, audit.private_target(audit.ROOT, audit.PID, data))
        self.assertEqual("2026-10-06T16:04:24.4330550Z", identity["birth_utc"])

    def test_missing_or_unstopped_java_native_proof_rejected(self):
        identity = {"birth_utc": "2026-10-06T16:04:24.4330550Z"}
        for values in ([], [{"attestation_child_stopped": False, "records": []}]):
            data = {"results": {"private_target": identity}, "java_final": {"native_attestations": values}}
            with self.subTest(values=values), patch.object(audit.base, "PrivateTarget") as target, self.assertRaises(RuntimeError):
                target.return_value.verify.return_value = identity
                audit.private_target(audit.ROOT, audit.PID, data)

    def test_ledger_uses_readonly_query_only_and_closes(self):
        connection = MagicMock()
        connection.execute.return_value.fetchall.return_value = []
        with patch.object(audit.sqlite3, "connect", return_value=connection) as connect:
            result = audit.ledger_snapshot()
        self.assertTrue(connect.call_args.args[0].endswith("?mode=ro")); self.assertTrue(connect.call_args.kwargs["uri"])
        self.assertIn(unittest.mock.call("PRAGMA query_only=ON"), connection.execute.call_args_list)
        self.assertEqual(set(audit.legacy.SQLITE_TABLES), set(result)); connection.close.assert_called_once()

    def test_any_original_ledger_table_changed_rejected(self):
        original = {key: [] for key in audit.legacy.SQLITE_TABLES}
        for key in original:
            changed = copy.deepcopy(original); changed[key] = [{"changed": True}]
            with self.subTest(key=key), patch.object(audit, "ledger_snapshot", return_value=changed), patch.object(audit.continued, "original_absent") as native, self.assertRaises(RuntimeError):
                audit.quiescence({"ledger": original})
            native.assert_not_called()

    def test_protocol_task_decision_budget_bool_and_retry_rejected(self):
        for key, wrong in (("protocol_version", True), ("protocol_version", 2), ("task_id", "D104"), ("decision", "accepted"), ("new_DDL", 2), ("new_DML", 1), ("new_ILP", 1), ("base_writes", False), ("formal_writes", 1), ("automatic_retry", True)):
            value = gate(); value[key] = wrong
            args = MagicMock(); args.create_admission = audit.DIRECTORY / "pure-gate.json"
            with self.subTest(key=key, wrong=wrong), patch.object(audit, "load", return_value=value), patch.object(sut, "validate_code") as code, self.assertRaises(RuntimeError):
                sut.admission_inputs(args)
            code.assert_not_called()

    def test_wrong_admitted_code_sha_rejected(self):
        with patch.object(audit, "binding", return_value={"path": str(sut.SCRIPT), "sha256": "a" * 64}), self.assertRaises(RuntimeError):
            sut.validate_code({"script": {"path": str(sut.SCRIPT), "sha256": "b" * 64}})

    def test_wrong_admitted_code_path_rejected(self):
        with patch.object(audit, "binding", return_value={"path": str(sut.SCRIPT), "sha256": "a" * 64}), self.assertRaises(RuntimeError):
            sut.validate_code({"script": {"path": str(audit.REPO / "wrong.py"), "sha256": "a" * 64}})

    def test_static_review_failed_or_missing_helpers_rejected(self):
        for review in ({"protocol_version": 1, "task_id": "D105", "status": "FAILED", "blockers": []}, {"protocol_version": 1, "task_id": "D105", "status": "PASS", "blockers": [], "bindings": []}):
            with self.subTest(review=review), patch.object(audit, "binding", return_value={}), patch.object(sut, "evidence", return_value=review), self.assertRaises(RuntimeError):
                sut.validate_code({"script": {}, "independent_static_review": {}})

    def test_existing_view_or_protected_frontier_changed_rejected(self):
        previous = {"private_after": {"txn": 1}}
        for current in ({"private_before": {"txn": 2}, "private_after": {"txn": 2}}, {"private_before": {"txn": 1}, "private_after": {"txn": 2}}):
            with self.subTest(current=current), self.assertRaises(RuntimeError):
                sut.compare_frontier(current, previous, True)


class CreateOnceTests(unittest.TestCase):
    def context(self):
        identity, producer = {"pid": audit.PID}, {"pid": 123, "birth_utc": "2026-10-07T00:00:00Z"}
        result = {"invocation_id": "pure", "owner_contract": audit.owner_contract(), "private_target_attestation": identity, "producer_identity": producer, "create_admission": {}, "attempted_DDL": 0, "acknowledged_DDL": 0}
        target = MagicMock(); target.verify.return_value = identity
        return target, producer, result

    def mocks(self, producer, events):
        def save(path, value):
            events.append(("save", Path(path), copy.deepcopy(value)))
        return (patch.object(audit, "save_new", side_effect=save), patch.object(sut, "save_progress", side_effect=save), patch.object(sut, "digest", return_value="a" * 64), patch.object(sut.base, "producer_identity", return_value=producer), patch.object(sut, "save_raw", side_effect=lambda path, body: events.append(("raw", body)) or {"path": str(path), "sha256": "b" * 64, "bytes": len(body)}))

    def test_only_private_identity_create_allowed(self):
        sut.validate_ddl(audit.owner_contract()["private_ddl"])
        for sql in ("CREATE VIEW v_macro_core_monthly AS (SELECT * FROM macro_core_monthly)", "CREATE OR REPLACE VIEW " + audit.PRIVATE_VIEW + " AS (SELECT * FROM " + audit.PRIVATE_BASE + ")", "INSERT INTO " + audit.PRIVATE_BASE + " VALUES(1)", audit.owner_contract()["private_ddl"] + ";", "DROP VIEW " + audit.PRIVATE_VIEW):
            with self.subTest(sql=sql), self.assertRaises(RuntimeError):
                sut.validate_ddl(sql)

    def test_cancellation_before_claim_prevents_http(self):
        target, producer, result = self.context()
        with patch.object(sut.common, "check_cancel", side_effect=RuntimeError("cancelled")), patch.object(audit, "save_new") as save, patch.object(sut, "urlopen") as http, self.assertRaises(RuntimeError):
            sut.submit_once(target, result, MagicMock())
        save.assert_not_called(); http.assert_not_called()

    def test_existing_claim_refuses_http_resend(self):
        target, producer, result = self.context()
        with patch.object(sut.base, "producer_identity", return_value=producer), patch.object(audit, "save_new", side_effect=FileExistsError("existing claim")), patch.object(sut, "urlopen") as http, self.assertRaises(FileExistsError):
            sut.submit_once(target, result, MagicMock())
        http.assert_not_called()

    def test_unknown_http_preserves_durable_intent_without_retry(self):
        target, producer, result = self.context(); events = []; patches = self.mocks(producer, events)
        with patches[0], patches[1], patches[2], patches[3], patches[4], patch.object(sut, "urlopen", side_effect=OSError("unknown ACK")) as http, self.assertRaises(OSError):
            sut.submit_once(target, result, lambda: events.append(("boundary",)))
        http.assert_called_once(); self.assertEqual("UNKNOWN", result["create_operation"]["ack"]); self.assertEqual(0, result["acknowledged_DDL"])
        intents = [item[2] for item in events if item[0] == "save" and item[1] == sut.CLAIM]
        self.assertEqual("UNKNOWN", intents[0]["ack"]); self.assertFalse(intents[0]["request_started"]); self.assertTrue(intents[-1]["request_started"])
        self.assertEqual("boundary", events[0][0])

    def test_raw_bytes_saved_before_ddl_ack_is_accepted(self):
        target, producer, result = self.context(); events = []; patches = self.mocks(producer, events)
        response = MagicMock(); response.__enter__.return_value = response; response.status = 200; response.read.return_value = b'{"ddl":"OK"}'
        with patches[0], patches[1], patches[2], patches[3], patches[4], patch.object(sut, "urlopen", return_value=response) as http:
            sut.submit_once(target, result, MagicMock())
        self.assertEqual(1, result["acknowledged_DDL"]); self.assertEqual("ACKNOWLEDGED", result["create_operation"]["ack"]); http.assert_called_once()
        raw_index = next(index for index, value in enumerate(events) if value[0] == "raw")
        ack_index = next(index for index, value in enumerate(events) if value[0] == "save" and value[2].get("ack") == "ACKNOWLEDGED")
        self.assertLess(raw_index, ack_index); self.assertEqual(20, http.call_args.kwargs["timeout"])

    def test_dml_payload_does_not_upgrade_create_unknown(self):
        target, producer, result = self.context(); events = []; patches = self.mocks(producer, events)
        response = MagicMock(); response.__enter__.return_value = response; response.status = 200; response.read.return_value = b'{"dml":"OK","updated":1}'
        with patches[0], patches[1], patches[2], patches[3], patches[4], patch.object(sut, "urlopen", return_value=response) as http, self.assertRaises(RuntimeError):
            sut.submit_once(target, result, MagicMock())
        http.assert_called_once(); self.assertEqual("UNKNOWN", result["create_operation"]["ack"]); self.assertEqual(0, result["acknowledged_DDL"])
        self.assertTrue(any(value[0] == "raw" for value in events))

    def test_duplicate_response_keys_remain_unknown(self):
        target, producer, result = self.context(); events = []; patches = self.mocks(producer, events)
        response = MagicMock(); response.__enter__.return_value = response; response.status = 200; response.read.return_value = b'{"ddl":"OK","ddl":"OK"}'
        with patches[0], patches[1], patches[2], patches[3], patches[4], patch.object(sut, "urlopen", return_value=response), self.assertRaises(RuntimeError):
            sut.submit_once(target, result, MagicMock())
        self.assertEqual("UNKNOWN", result["create_operation"]["ack"])

    def test_cancellation_after_durable_claim_does_not_send(self):
        target, producer, result = self.context(); events = []; patches = self.mocks(producer, events)
        with patches[0], patches[1], patches[2], patches[3], patches[4], patch.object(sut.common, "check_cancel", side_effect=[None, RuntimeError("cancelled")]), patch.object(sut, "urlopen") as http, self.assertRaises(RuntimeError):
            sut.submit_once(target, result, MagicMock())
        http.assert_not_called(); self.assertFalse(result["create_operation"]["request_started"]); self.assertEqual("UNKNOWN", result["create_operation"]["ack"])

    def test_native_server_or_producer_changed_refuses_claim(self):
        target, producer, result = self.context(); target.verify.return_value = {"pid": 1}
        with patch.object(sut.base, "producer_identity", return_value=producer), patch.object(audit, "save_new") as save, patch.object(sut, "urlopen") as http, self.assertRaises(RuntimeError):
            sut.submit_once(target, result, MagicMock())
        save.assert_not_called(); http.assert_not_called()


if __name__ == "__main__":
    unittest.main()
