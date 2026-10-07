"""Pure known-CPI continuation guards; no actual DB/native/HTTP or old edits."""
from __future__ import annotations

import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import prepare_d104_macro_core_initial_after_known_partial as sut
import test_d104_fixture_guards as old_samples
import test_d104_preflight_guards as samples


def physical(txn=1, count=2):
    return {"id": 9, "directoryName": "cn_ppi~9", "table_txn": txn, "table_row_count": count,
            "partitionBy": "YEAR", "designatedTimestamp": "month", "walEnabled": True, "dedup": True,
            "table_suspended": False, "wal_pending_row_count": 0}


def wal(txn=1):
    return {"sequencerTxn": txn, "writerTxn": txn, "bufferedTxnSize": 0, "suspended": False}


def reader(rows):
    value = MagicMock(); value.visibility_deadline = None; value.cancel_file = None
    value.generation_observation_retries = 0; value.records.side_effect = rows
    return value


def stable(p, w, count):
    return [[p], [w], [{"n": count}], [p], [w]]


class StableReadOnlyObservationTests(unittest.TestCase):
    def test_final_private_frontier_is_fresh_after_full_readback(self):
        tables = (*sut.audit.SOURCES, *sut.PROTECTED)
        state = {table: {"exists": False} for table in tables}
        with patch.object(sut, "private_state", side_effect=list(state.values())) as observe:
            self.assertEqual(sut.final_private_frontier(MagicMock(), state, copy.deepcopy(state)), state)
        self.assertEqual([call.args[1] for call in observe.call_args_list], list(tables))

    def test_post_readback_txn_id_or_count_drift_rejected(self):
        tables = (*sut.audit.SOURCES, *sut.PROTECTED)
        old = {table: {"exists": True, "physical": physical(), "wal": wal(), "actual_select_count": 2, "settled": True} for table in tables}
        for field in ("table_txn", "id", "table_row_count"):
            fresh = copy.deepcopy(old); fresh["cn_ppi"]["physical"][field] += 1
            with patch.object(sut, "private_state", side_effect=list(fresh.values())), self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.final_private_frontier(MagicMock(), old, copy.deepcopy(old))

    def test_original_pre_readback_frontier_difference_rejected(self):
        state = {table: {"exists": False} for table in (*sut.audit.SOURCES, *sut.PROTECTED)}
        earlier = copy.deepcopy(state); earlier["cn_cpi"]["exists"] = True
        with patch.object(sut, "private_state", side_effect=list(state.values())), self.assertRaises(RuntimeError):
            sut.final_private_frontier(MagicMock(), state, earlier)

    def test_stable_full_generation(self):
        value = reader(stable(physical(), wal(), 2))
        state = sut.private_state(value, "cn_ppi")
        self.assertEqual(state["physical"], physical()); self.assertEqual(state["actual_select_count"], 2)
        self.assertTrue(state["settled"]); self.assertEqual(value.records.call_count, 5)

    def test_exact_observed_null_to_two_row_race_retries_select_only(self):
        empty, before = physical(None, None), wal(0)
        filled, after = physical(), wal()
        value = reader([[empty], [before], [{"n": 2}], [filled], [after], *stable(filled, after, 2)])
        with patch.object(sut.time, "sleep") as sleep:
            state = sut.private_state(value, "cn_ppi")
        self.assertEqual(state["physical"]["table_txn"], 1)
        self.assertEqual(value.generation_observation_retries, 1); sleep.assert_called_once_with(0.1)
        self.assertTrue(all(call.args[0].split()[0] == "SELECT" for call in value.records.call_args_list))
        self.assertIsNone(value.visibility_deadline)

    def test_consistent_actual_empty_preserves_raw_null(self):
        value = reader(stable(physical(None, None), wal(0), 0))
        state = sut.private_state(value, "cn_ppi")
        self.assertIsNone(state["physical"]["table_txn"]); self.assertIsNone(state["physical"]["table_row_count"])
        self.assertEqual(state["actual_select_count"], 0)

    def test_null_metadata_count_two_never_coerced_to_zero(self):
        value = reader(stable(physical(None, None), wal(0), 2))
        with patch.object(sut.time, "monotonic", side_effect=[0, 0, 31]), patch.object(sut.time, "sleep"), self.assertRaises(RuntimeError):
            sut.private_state(value, "cn_ppi")
        self.assertEqual(value.records.call_count, 5); self.assertIsNone(value.visibility_deadline)

    def test_count_before_metadata_rowcount_update_retries(self):
        old, new = physical(1, 0), physical(1, 2)
        value = reader([[old], [wal()], [{"n": 2}], [new], [wal()], *stable(new, wal(), 2)])
        with patch.object(sut.time, "sleep"):
            state = sut.private_state(value, "cn_ppi")
        self.assertEqual(state["actual_select_count"], 2); self.assertEqual(value.generation_observation_retries, 1)

    def test_wal_frontier_changed_inside_count_retries(self):
        value = reader([[physical()], [wal(0)], [{"n": 2}], [physical()], [wal(1)], *stable(physical(), wal(), 2)])
        with patch.object(sut.time, "sleep"):
            state = sut.private_state(value, "cn_ppi")
        self.assertEqual(state["wal"]["sequencerTxn"], 1); self.assertEqual(value.generation_observation_retries, 1)

    def test_new_table_visibility_after_missing_observation(self):
        value = reader([[], [physical()], *stable(physical(), wal(), 2)])
        with patch.object(sut.time, "sleep"):
            state = sut.private_state(value, "cn_ppi")
        self.assertTrue(state["exists"]); self.assertEqual(value.generation_observation_retries, 1)

    def test_absence_proved_twice_without_count(self):
        value = reader([[], []])
        self.assertEqual(sut.private_state(value, "cn_ppi"), {"exists": False})
        self.assertEqual(value.records.call_count, 2)

    def test_replaced_physical_id_refused_no_more_queries(self):
        changed = physical(); changed["id"] = 10; changed["directoryName"] = "cn_ppi~10"
        value = reader([[physical()], [wal()], [{"n": 2}], [changed], [wal()]])
        with self.assertRaises(RuntimeError):
            sut.private_state(value, "cn_ppi")
        self.assertEqual(value.generation_observation_retries, 0)

    def test_disappeared_table_refused(self):
        value = reader([[physical()], [wal()], [{"n": 2}], [], []])
        with self.assertRaises(RuntimeError):
            sut.private_state(value, "cn_ppi")

    def test_invalid_bool_or_negative_counter_rejected(self):
        for key, wrong in (("table_txn", True), ("table_row_count", False), ("wal_pending_row_count", -1)):
            p = physical(); p[key] = wrong
            value = reader(stable(p, wal(), 2))
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                sut.private_state(value, "cn_ppi")

    def test_unknown_table_refused_before_select(self):
        value = reader([])
        with self.assertRaises(RuntimeError):
            sut.private_state(value, "other")
        value.records.assert_not_called()

    def test_cancel_before_select(self):
        value = reader([])
        with patch.object(sut.audit, "check_cancel", side_effect=RuntimeError("cancelled")), self.assertRaises(RuntimeError):
            sut.private_state(value, "cn_ppi")
        value.records.assert_not_called()

    def test_outer_visibility_deadline_not_extended(self):
        value = reader([]); value.visibility_deadline = 5
        with patch.object(sut.time, "monotonic", return_value=6), self.assertRaises(RuntimeError):
            sut.private_state(value, "cn_ppi")
        self.assertEqual(value.visibility_deadline, 5); value.records.assert_not_called()


class AdmissionAndProvenanceTests(unittest.TestCase):
    def test_cpi_or_output_claims_are_never_available(self):
        for table in ("cn_cpi", sut.base.PRIVATE_OUTPUT, sut.audit.TARGET):
            with self.subTest(table=table), self.assertRaises(RuntimeError):
                sut.claim_path("DML", table)

    def test_remaining_claim_names_are_separate_from_old_claims(self):
        for table in sut.REMAINING:
            for kind in ("DDL", "DML"):
                self.assertNotEqual(sut.claim_path(kind, table), sut.base.claim_path(kind, table))
                self.assertIn("remaining", sut.claim_path(kind, table).name)

    def test_cpi_submission_refused_before_boundary(self):
        boundary = MagicMock()
        with patch.object(sut, "urlopen") as http, self.assertRaises(RuntimeError):
            sut.submit_once(MagicMock(), "DML", "cn_cpi", samples.models()["cn_cpi"], samples.sample()["cn_cpi"][:2], sut.OUTPUT, {}, boundary)
        http.assert_not_called(); boundary.assert_not_called()

    def test_output_submission_refused_before_boundary(self):
        with patch.object(sut, "urlopen") as http, self.assertRaises(RuntimeError):
            sut.submit_once(MagicMock(), "DDL", sut.audit.TARGET, samples.models()[sut.audit.TARGET], [], sut.OUTPUT, {}, MagicMock())
        http.assert_not_called()

    def test_original_unknown_or_extra_operation_not_admitted(self):
        failed = {"task_id": "D104", "status": "FAILED", "script_sha256": sut.BASE_SHA, "automatic_retry": False,
            "error": {"type": "RuntimeError", "message": "Null private txn requires actual empty proof"},
            "attempted_operations": 2, "acknowledged_operations": 2, "submitted_source_rows": 2, "acknowledged_source_rows": 2,
            "operations": [{"table": "cn_cpi", "kind": "DDL", "ack": "ACKNOWLEDGED", "rows": 0}, {"table": "cn_cpi", "kind": "DML", "ack": "UNKNOWN", "rows": 2}]}
        with patch.object(sut.base, "load") as load, self.assertRaises(RuntimeError):
            sut.validate_known_operations(failed, {}, {})
        load.assert_not_called()

    def test_original_other_failure_not_admitted(self):
        value = {"task_id": "D104", "status": "FAILED", "script_sha256": sut.BASE_SHA, "automatic_retry": False, "error": {"type": "RuntimeError", "message": "UNKNOWN transport"}}
        with self.assertRaises(RuntimeError):
            sut.validate_known_operations(value, {}, {})

    def test_noexecute_before_admission_or_native(self):
        args = MagicMock(); args.execute_isolated = False
        with patch.object(sut, "admission_inputs") as admission, patch.object(sut.base, "native_query") as native, self.assertRaises(RuntimeError):
            sut.run(args, sut.OUTPUT, {})
        admission.assert_not_called(); native.assert_not_called()

    def test_original_producer_present_or_unknown_birth_rejected(self):
        original = old_samples.producer()
        for birth in (original["birth_utc"], None, "2026-10-06T16:04:00Z"):
            with patch.object(sut.base, "native_query", return_value={"matches": [{"pid": original["pid"], "birth_utc": birth, "name": "python.exe"}]}), self.subTest(birth=birth), self.assertRaises(RuntimeError):
                sut.original_absent(original)

    def test_known_later_reused_pid_does_not_claim_original_present(self):
        original = old_samples.producer()
        with patch.object(sut.base, "native_query", return_value={"matches": [{"pid": original["pid"], "birth_utc": "2026-10-06T16:06:00Z", "name": "other.exe"}]}):
            self.assertFalse(sut.original_absent(original)["original_identity_present"])


class DurableOneShotTests(unittest.TestCase):
    def _attempt(self, body=b'{"dml":"OK","updated":2}', failure=None, old_claim=False):
        value = {"invocation_id": "pure", "remaining_operations": [], "new_attempted_operations": 0, "new_submitted_source_rows": 0, "new_acknowledged_operations": 0, "new_acknowledged_source_rows": 0,
                 "private_target_attestation": old_samples.identity(), "producer_identity": old_samples.producer(), "partial_admission": {"path": "pure", "sha256": "a" * 64}, "failed_initial": {"path": "pure", "sha256": "b" * 64}}
        target = MagicMock(); target.verify.return_value = old_samples.identity()
        response = MagicMock(); response.status = 200; response.__enter__.return_value = response; response.read.return_value = body
        events = []
        def journal(path, record):
            events.append(("journal", copy.deepcopy(record)))
        def claim(path, record):
            events.append(("claim", copy.deepcopy(record)))
            if old_claim:
                raise FileExistsError("claim retained")
        def raw(path, record):
            events.append(("raw", record))
            return {"path": str(path), "sha256": "c" * 64, "bytes": len(record)}
        def http(*_args, **_kwargs):
            self.assertEqual(value["remaining_operations"][0]["ack"], "UNKNOWN")
            events.append("http")
            if failure:
                raise failure
            return response
        with patch.object(sut.base, "producer_identity", return_value=old_samples.producer()), patch.object(sut.audit, "save_new", side_effect=claim), patch.object(sut.base, "save_progress", side_effect=journal), \
             patch.object(sut.audit, "digest", return_value="d" * 64), patch.object(sut.base, "save_raw", side_effect=raw), patch.object(sut, "urlopen", side_effect=http) as send:
            try:
                sut.submit_once(target, "DML", "cn_ppi", samples.models()["cn_ppi"], samples.sample()["cn_ppi"][:2], sut.OUTPUT, value, MagicMock())
                error = None
            except BaseException as caught:
                error = caught
        return value, events, send.call_count, error

    def test_new_claim_unknown_before_send_and_raw_before_ack(self):
        value, events, count, error = self._attempt()
        self.assertIsNone(error); self.assertEqual(count, 1)
        claim_index = next(i for i, item in enumerate(events) if isinstance(item, tuple) and item[0] == "claim")
        raw_index = next(i for i, item in enumerate(events) if isinstance(item, tuple) and item[0] == "raw")
        ack_index = next(i for i, item in enumerate(events) if isinstance(item, tuple) and item[0] == "journal" and item[1].get("ack") == "ACKNOWLEDGED")
        self.assertEqual(events[claim_index][1]["ack"], "UNKNOWN"); self.assertLess(claim_index, events.index("http")); self.assertLess(raw_index, ack_index)
        self.assertEqual((value["new_acknowledged_operations"], value["new_acknowledged_source_rows"]), (1, 2))

    def test_unknown_network_never_retry(self):
        value, events, count, error = self._attempt(failure=TimeoutError("unknown"))
        self.assertIsInstance(error, TimeoutError); self.assertEqual(count, 1)
        self.assertEqual(value["remaining_operations"][0]["ack"], "UNKNOWN"); self.assertEqual(value["new_acknowledged_operations"], 0)

    def test_wrong_ddl_ack_not_promoted_to_dml(self):
        value, events, count, error = self._attempt(body=b'{"ddl":"OK"}')
        self.assertIsInstance(error, RuntimeError); self.assertEqual(count, 1)
        self.assertEqual(value["remaining_operations"][0]["ack"], "UNKNOWN"); self.assertTrue(any(isinstance(item, tuple) and item[0] == "raw" for item in events))

    def test_existing_continuation_claim_no_http(self):
        value, events, count, error = self._attempt(old_claim=True)
        self.assertIsInstance(error, FileExistsError); self.assertEqual(count, 0)
        self.assertEqual(value["remaining_operations"], [])

    def test_cancel_before_new_claim(self):
        with patch.object(sut.audit, "check_cancel", side_effect=RuntimeError("cancel")), patch.object(sut.audit, "save_new") as save, patch.object(sut, "urlopen") as send, self.assertRaises(RuntimeError):
            sut.submit_once(MagicMock(), "DML", "cn_ppi", samples.models()["cn_ppi"], samples.sample()["cn_ppi"][:2], sut.OUTPUT, {"remaining_operations": []}, MagicMock(), "cancel")
        save.assert_not_called(); send.assert_not_called()


if __name__ == "__main__":
    unittest.main()
