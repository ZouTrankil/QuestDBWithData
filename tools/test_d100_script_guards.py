"""Pure D100 script regressions; no CIM, HTTP, PG, ILP or database access."""
from __future__ import annotations

import copy
from datetime import datetime
import importlib
import logging
import hashlib
import json
import math
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import MagicMock, patch

import pandas as pd

sys.dont_write_bytecode = True
TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
# Import the real Python model/owner without configuring its external file logger.
# The model, source SQL, normalization and publisher code remain unchanged.
log_module = types.ModuleType("config.logger")
log_module.get_logger = logging.getLogger
for name in ("app_logger", "sync_logger", "db_logger", "api_logger"):
    setattr(log_module, name, logging.getLogger("d100-pure-tests"))
for name in ("debug", "info", "warning", "error", "critical"):
    setattr(log_module, name, getattr(logging.getLogger("d100-pure-tests"), name))
with patch.dict(sys.modules, {"config.logger": log_module}):
    sut = importlib.import_module("accept_d100_cache_isolated")


def real_model_frame(rows=1):
    record = {field: ("20260917" if kind == "TIMESTAMP" else "a" * 64 if kind == "SYMBOL" else
                      2 if kind == "LONG" else 0.125)
              for field, kind in sut.SPEC.model.get_questdb_schema()["schema"].items()}
    return pd.DataFrame([{**record, "trade_date": f"202609{17 + index:02d}"} for index in range(rows)])


class D100GuardTests(unittest.TestCase):
    def setUp(self):
        self.target = MagicMock(name="private_attestation")
        self.connection = MagicMock(name="private_pg_connection")
        self.cursor = self.connection.cursor.return_value.__enter__.return_value
        self.cursor.description = [("trade_date", 1114), ("total_q1", 20), ("avg_retail_ratio", 701)]
        self.cursor.fetchall.return_value = [(datetime(2026, 9, 17), 2, 0.125)]
        self.sender_class = MagicMock(name="Sender")
        self.sender = self.sender_class.from_conf.return_value
        self.qwp = MagicMock(name="private_qwp", return_value=[])
        self.connect = MagicMock(name="private_pg_connect", return_value=self.connection)
        patches = (
            patch.object(sut.fixture, "PRIVATE_TARGET", self.target),
            patch.object(sut.fixture, "qwp", self.qwp),
            patch.object(sut.fixture.subprocess, "run", side_effect=AssertionError("CIM is forbidden in pure tests")),
            patch.object(sut.psycopg2, "connect", self.connect),
            patch.object(sut, "Sender", self.sender_class),
        )
        for replacement in patches:
            replacement.start()
            self.addCleanup(replacement.stop)
        self.result = {"owner_submissions": [], "phases": []}
        self.client = sut.OwnerClient(self.result, TOOLS / "unused-d100-test-output.json")
        self.client.phase = "first"
        self.client.save = MagicMock(name="save")
        self.target.verify.reset_mock()

    def test_query_accepts_original_multiline_select(self):
        sql = sut.fixture.common.VIEW_SELECTS[sut.SPEC.view].format(
            where="WHERE ts >= '2026-09-17' AND trade_date < '2026-09-19'")
        self.assertTrue(sql.startswith("SELECT\n"))
        self.assertEqual([], sut.query(sql))
        self.qwp.assert_called_once_with(sql)

    def test_query_rejects_empty_nonselect_and_semicolon_before_qwp(self):
        for sql in ("", " \n\t", "UPDATE cache SET value=1", "CREATE TABLE x(n INT)",
                    "REFRESH MATERIALIZED VIEW x FULL", "SELECT 1;", "SELECT 1; SELECT 2"):
            with self.subTest(sql=sql), self.assertRaises(RuntimeError):
                sut.query(sql)
        self.qwp.assert_not_called()
        self.target.verify.assert_not_called()

    def test_owner_fetch_accepts_original_multiline_select_and_binds(self):
        sql = sut.fixture.common.VIEW_SELECTS[sut.SPEC.view].format(
            where="WHERE ts >= %s AND ts <= %s")
        binds = ("2026-09-17", "2026-09-18")
        frame = self.client.fetch_df(sql, binds)
        self.cursor.execute.assert_called_once_with(sql, binds)
        self.assertEqual(pd.Timestamp("2026-09-17"), frame.iloc[0].trade_date)
        self.assertEqual("int64", str(frame.total_q1.dtype))
        self.assertEqual("float64", str(frame.avg_retail_ratio.dtype))
        self.sender_class.from_conf.assert_not_called()

    def test_owner_fetch_rejects_empty_nonselect_and_semicolon_before_cursor(self):
        for sql in ("", " \n\t", "INSERT INTO cache VALUES(1)", "DELETE FROM cache",
                    "WITH a AS (SELECT 1) SELECT * FROM a", "SELECT 1;", "SELECT 1; DROP TABLE x"):
            with self.subTest(sql=sql), self.assertRaises(RuntimeError):
                self.client.fetch_df(sql, ("unused",))
        self.connection.cursor.assert_not_called()
        self.sender_class.from_conf.assert_not_called()

    def test_write_rejects_unregistered_table_before_preparation_and_send(self):
        model = MagicMock(name="unregistered_model")
        model.get_questdb_schema.return_value = {"table_name": "unregistered_cache"}
        with self.assertRaises(RuntimeError):
            self.client.write_model(model, real_model_frame())
        model.prepare_questdb_dataframe.assert_not_called()
        self.sender_class.from_conf.assert_not_called()
        self.target.verify.assert_not_called()
        self.client.save.assert_not_called()
        self.assertEqual([], self.result["owner_submissions"])

    def test_write_rejects_impostor_of_allowlisted_table_before_send(self):
        model = MagicMock(name="impostor_cache_model")
        model.get_questdb_schema.return_value = sut.SPEC.model.get_questdb_schema()
        with self.assertRaises(RuntimeError):
            self.client.write_model(model, real_model_frame())
        model.prepare_questdb_dataframe.assert_not_called()
        self.sender_class.from_conf.assert_not_called()
        self.target.verify.assert_not_called()
        self.client.save.assert_not_called()

    def test_write_rejects_zero_and_more_than_two_rows_before_preparation(self):
        prepare = sut.SPEC.model.prepare_questdb_dataframe
        with patch.object(sut.SPEC.model, "prepare_questdb_dataframe", wraps=prepare) as normalize:
            for rows in (0, 3):
                with self.subTest(rows=rows), self.assertRaises(RuntimeError):
                    self.client.write_model(sut.SPEC.model, real_model_frame(rows))
            normalize.assert_not_called()
        self.sender_class.from_conf.assert_not_called()
        self.target.verify.assert_not_called()
        self.client.save.assert_not_called()
        self.assertEqual([], self.result["owner_submissions"])

    def test_unknown_flush_keeps_unknown_ack_saves_before_send_and_never_retries(self):
        events, saved = [], []
        def save():
            events.append("save")
            saved.append(copy.deepcopy(self.result))
        def flush():
            events.append("flush")
            raise TimeoutError("ambiguous private ILP response")
        self.client.save.side_effect = save
        self.sender.flush.side_effect = flush
        self.sender.close.side_effect = lambda **kwargs: events.append(("close", kwargs))
        frame = real_model_frame()
        expected = sut.SPEC.model.prepare_questdb_dataframe(frame)
        with self.assertRaises(TimeoutError):
            self.client.write_model(sut.SPEC.model, frame)
        self.assertEqual(["save", "flush", ("close", {"flush": False})], events)
        self.assertEqual("UNKNOWN", saved[0]["owner_submissions"][0]["ack"])
        self.assertEqual("UNKNOWN", self.result["owner_submissions"][0]["ack"])
        self.assertFalse(self.result["owner_submissions"][0]["automatic_retry"])
        self.assertEqual([], self.result["phases"])
        self.sender_class.from_conf.assert_called_once_with(
            "http::addr=127.0.0.1:19010;", auto_flush=False, retry_timeout=0, request_timeout=10000)
        self.sender.establish.assert_called_once_with()
        self.sender.flush.assert_called_once_with()
        self.sender.close.assert_called_once_with(flush=False)
        self.sender.dataframe.assert_called_once()
        pd.testing.assert_frame_equal(expected, self.sender.dataframe.call_args.args[0])
        self.assertEqual(sut.CACHE, self.sender.dataframe.call_args.kwargs["table_name"])
        self.assertEqual(["source_version"], self.sender.dataframe.call_args.kwargs["symbols"])
        self.assertEqual("trade_date", self.sender.dataframe.call_args.kwargs["at"])
        self.assertEqual(2, self.target.verify.call_count)
        self.cursor.execute.assert_not_called()

    def test_invalid_generation_counts_and_duplicate_keys_never_reach_sender(self):
        for field, value in (("source_version", "bad"), ("source_version", None),
                             ("total_q1", -1), ("total_q1", None), ("avg_retail_ratio", math.inf)):
            frame = real_model_frame()
            frame[field] = value
            with self.subTest(field=field, value=value), self.assertRaises((RuntimeError, ValueError, TypeError)):
                self.client.write_model(sut.SPEC.model, frame)
        with self.assertRaises(RuntimeError):
            self.client.write_model(sut.SPEC.model, pd.concat([real_model_frame(), real_model_frame()], ignore_index=True))
        self.sender_class.from_conf.assert_not_called()
        self.client.save.assert_not_called()
        self.assertEqual([], self.result["owner_submissions"])

    def test_second_attestation_failure_keeps_unknown_and_does_not_flush(self):
        self.target.verify.side_effect = [self.target.identity, RuntimeError("private PID changed")]
        with self.assertRaises(RuntimeError):
            self.client.write_model(sut.SPEC.model, real_model_frame())
        self.sender.flush.assert_not_called()
        self.sender.close.assert_called_once_with(flush=False)
        self.assertEqual("UNKNOWN", self.result["owner_submissions"][0]["ack"])
        self.assertEqual(0, self.result["owner_submissions"][0]["acknowledged_rows"])
        self.client.save.assert_called_once_with()

    def test_unknown_and_ack_counts_describe_intent_separately(self):
        saved = []
        self.client.save.side_effect = lambda: saved.append(copy.deepcopy(self.result))
        self.client.write_model(sut.SPEC.model, real_model_frame(2))
        before, after = [item["owner_submissions"][0] for item in saved]
        self.assertEqual((2, 2, 0), (before["planned_rows"], before["attempted_rows"], before["acknowledged_rows"]))
        self.assertEqual((2, 2, 2), (after["planned_rows"], after["attempted_rows"], after["acknowledged_rows"]))
        self.assertIn("server application requires exact readback", before["attempted_rows_semantics"])

    def test_local_sender_pack_failure_does_not_flush_or_continue(self):
        self.sender.dataframe.side_effect = ValueError("local serialization failure")
        with self.assertRaises(ValueError):
            self.client.write_model(sut.SPEC.model, real_model_frame())
        self.sender.flush.assert_not_called()
        self.sender.close.assert_called_once_with(flush=False)
        self.sender_class.from_conf.assert_called_once()
        self.assertEqual("UNKNOWN", self.result["owner_submissions"][0]["ack"])
        self.assertEqual([], self.result["phases"])
        self.client.save.assert_called_once_with()

    def test_acknowledged_send_uses_real_model_and_persists_ack_after_one_flush(self):
        saved = []
        self.client.save.side_effect = lambda: saved.append(copy.deepcopy(self.result))
        result = self.client.write_model(sut.SPEC.model, real_model_frame(2))
        self.assertEqual({"success": True}, result)
        self.assertEqual(["UNKNOWN", "ACKNOWLEDGED"], [item["owner_submissions"][0]["ack"] for item in saved])
        self.assertEqual(2, self.result["owner_submissions"][0]["submitted_rows"])
        self.sender.flush.assert_called_once_with()
        self.sender.close.assert_called_once_with(flush=False)
        self.sender_class.from_conf.assert_called_once()
        self.assertEqual(2, self.target.verify.call_count)


def cache_record(day="2026-09-17"):
    result = sut.canonical(sut.SPEC.model.prepare_questdb_dataframe(real_model_frame()).to_dict("records"))[0]
    return {**result, "trade_date": day + "T00:00:00.000000Z"}


def cache_snapshot():
    return {"physical": {"id": 15, "directoryName": sut.CACHE + "~15", "table_txn": 1,
        "table_row_count": 2, "partitionBy": "MONTH", "walEnabled": True, "dedup": True,
        "designatedTimestamp": "trade_date", "table_suspended": False, "wal_pending_row_count": 0},
        "wal": {"sequencerTxn": 1, "writerTxn": 1, "bufferedTxnSize": 0, "suspended": False}, "settled": True}


def protected_snapshot():
    return {"tables": {
        sut.native.SOURCE: {"id": 9, "directoryName": sut.native.SOURCE + "~9", "table_txn": 36, "partitionBy": "DAY"},
        sut.native.MV: {"id": 11, "directoryName": sut.native.MV + "~11", "table_txn": 11}},
        "wal": {sut.native.SOURCE: {"sequencerTxn": 36, "writerTxn": 36},
                sut.native.MV: {"sequencerTxn": 11, "writerTxn": 11}},
        "mv": {"refresh_base_table_txn": 36, "base_table_txn": 36, "view_status": "valid"}}


def source_capture():
    return {"sourceId": 9, "sourceDirectory": sut.native.SOURCE + "~9", "sourceTableTxn": 36,
        "sourceSeqTxn": 36, "sourceWriterTxn": 36, "sourceSettled": True, "mvId": 11,
        "mvDirectory": sut.native.MV + "~11", "mvTxn": 11, "mvSeqTxn": 11, "mvWriterTxn": 11,
        "mvSettled": True, "valid": True, "caughtUp": True, "refreshBaseTxn": 36, "reportedBaseTxn": 36,
        "sourcePartition": "DAY", "viewStatus": "valid"}


def valid_capture():
    current = cache_snapshot()
    first = {"range_rows": [cache_record(), cache_record("2026-09-18")],
             "tables_after": {sut.CACHE: current}, "source_mv_alias_before": protected_snapshot()}
    first_bytes = json.dumps(first, separators=(",", ":")).encode("utf-8")
    descriptors = ", ".join(f"Column[sourceName={field}, logicalName={field}, storageName={field}, storageType={kind}, "
        f"nullable={'true' if kind == 'DOUBLE' else 'false'}, transform=identity]"
        for field, kind in sut.SPEC.model.get_questdb_schema()["schema"].items())
    parts = [sut.CACHE, 1, sut.CACHE, "[" + descriptors + "]", ["trade_date", "source_version"], list(sut.FIELDS),
             ["trade_date", "java.time.LocalDate:" + sut.native.START, "java.time.LocalDate:" + sut.native.STOP]]
    fingerprint = hashlib.sha256(json.dumps(parts, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).hexdigest()
    capture = {"status": "VERIFIED_ACTUAL_FIRST_CURSOR", "first_artifact": str(sut.FIRST),
        "first_artifact_sha256": hashlib.sha256(first_bytes).hexdigest(),
        "input_target": "attested-private-127.0.0.1:18822/19010", "private_data_root": str(sut.fixture.ROOT),
        "private_process_attested_before_and_after": True, "fields": list(sut.FIELDS),
        "range_from_inclusive": sut.native.START, "range_to_exclusive": sut.native.STOP,
        "query": {"columns": list(sut.FIELDS), "equalities": {}, "rangeColumn": "trade_date",
                  "fromInclusive": sut.native.START, "toExclusive": sut.native.STOP, "pageSize": 1, "cursor": None},
        "cache_snapshot_before": sut.java_snapshot(current), "cache_snapshot_after": sut.java_snapshot(current),
        "source_snapshot_before": source_capture(), "source_snapshot_after": source_capture(),
        "actual_first_rows": [{**cache_record(), "trade_date": "2026-09-17"}],
        "query_fingerprint_parts": parts, "query_fingerprint": fingerprint,
        "nextCursor": {"queryFingerprint": fingerprint, "sourceVersion": sut.cache_token(current),
                       "keyValues": ["2026-09-17", "a" * 64]}}
    return capture, first_bytes, first, current


class TypedIntegrityTests(unittest.TestCase):
    def test_empty_full_field_reads_certify_zero_and_preserve_null_physical_metadata(self):
        frames = {day: pd.DataFrame([{key: value for key, value in cache_record(pd.Timestamp(day).strftime("%Y-%m-%d")).items() if key != "source_version"}])
                  for day in sut.DAYS}
        versions = {day: "a" * 64 for day in sut.DAYS}
        client = MagicMock()
        client.fetch_df.side_effect = [pd.DataFrame(columns=sut.FIELDS), pd.DataFrame(columns=sut.COVERAGE_FIELDS)]
        metadata = cache_snapshot()
        metadata["physical"]["table_row_count"] = None
        with patch.object(sut, "table_state", return_value=metadata), patch.object(sut, "query") as query:
            result = sut.existing_records_match(frames, versions, client)
        self.assertEqual(0, result["cache_rows"])
        self.assertEqual(0, result["coverage_dataset_rows"])
        self.assertIsNone(result["raw_cache_table_row_count"])
        self.assertIn("metadata null is preserved", result["row_count_evidence"])
        self.assertEqual(2, client.fetch_df.call_count)
        self.assertTrue(all("LIMIT 201" in call.args[0] for call in client.fetch_df.call_args_list))
        self.assertIn(",".join(sut.FIELDS), client.fetch_df.call_args_list[0].args[0])
        self.assertIn(",".join(sut.COVERAGE_FIELDS), client.fetch_df.call_args_list[1].args[0])
        query.assert_not_called()

    def test_authoritative_full_fourteen_fields_reject_one_ulp_and_null_drift(self):
        original = cache_record()
        self.assertEqual(14, sut.compare_fields([original], [original], ("trade_date", "source_version"), sut.FIELDS))
        for field, value in (("avg_retail_ratio", math.nextafter(original["avg_retail_ratio"], math.inf)),
                             ("avg_mfi_score", None), ("total_q1", 3), ("source_version", "b" * 64)):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.compare_fields([{**original, field: value}], [original], ("trade_date", "source_version"), sut.FIELDS)

    def test_display_rounding_is_explicit_and_does_not_weaken_authoritative_comparison(self):
        stored = cache_record()
        display = {**stored, "avg_retail_ratio": math.nextafter(stored["avg_retail_ratio"], math.inf)}
        result = sut.transport_comparison([display], [stored])
        self.assertTrue(result["passed"])
        self.assertEqual(14, result["field_comparisons"])
        self.assertEqual(1, len(result["qwp_display_rounding"]))
        with self.assertRaises(RuntimeError):
            sut.compare_fields([display], [stored], ("trade_date", "source_version"), sut.FIELDS)

    def test_display_rejects_key_count_nonfinite_null_and_excess_drift(self):
        original = cache_record()
        for field, value in (("trade_date", "2026-09-18T00:00:00.000000Z"), ("source_version", "b" * 64),
                             ("total_q1", 3), ("avg_retail_ratio", math.inf), ("avg_mfi_score", None),
                             ("avg_retail_ratio", 0.126)):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.transport_comparison([{**original, field: value}], [original])

    def test_signed_doubles_nullable_fields_and_large_required_longs_keep_exact_values(self):
        record = {**cache_record(), "total_retail_net_inflow_yi": -345.5, "total_main_net_yi": -162.125,
                  "avg_mfi_score": None, "total_q1": 9007199254740993}
        sut.integrity.validate_cache_records([record])
        self.assertEqual(14, sut.compare_fields([record], [record], ("trade_date", "source_version"), sut.FIELDS))
        self.assertIsNone(sut.integrity.float_bits(record)["avg_mfi_score"])

    def test_cache_and_coverage_require_complete_typed_keys_and_counts(self):
        original = cache_record()
        for field, value in (("trade_date", None), ("trade_date", "2026-09-17"),
                             ("source_version", "A" * 64), ("total_q1", None), ("total_q1", True),
                             ("total_q1", 2.0), ("total_q1", -1), ("avg_retail_ratio", math.nan)):
            with self.subTest(field=field), self.assertRaises((RuntimeError, ValueError, TypeError)):
                sut.integrity.validate_cache_records([{**original, field: value}])
        receipt = {"trade_date": original["trade_date"], "dataset_id": sut.SPEC.dataset_id,
                   "source_version": "a" * 64, "row_count": 1, "content_digest": "b" * 64}
        sut.validate_coverage_records([receipt])
        for field, value in (("dataset_id", "market_breadth_daily"), ("row_count", 0), ("row_count", True),
                             ("source_version", None), ("content_digest", "bad"), ("trade_date", "2026-09-17")):
            with self.subTest(field=field), self.assertRaises((RuntimeError, ValueError, TypeError)):
                sut.validate_coverage_records([{**receipt, field: value}])


class CursorBindingTests(unittest.TestCase):
    def test_real_property_canonical_payload_sha_and_fixed_source_metadata_are_accepted(self):
        sut.validate_cursor_capture(*valid_capture())

    def test_legacy_fingerprint_property_is_rejected(self):
        capture, raw, first, current = valid_capture()
        capture["nextCursor"]["fingerprint"] = capture["nextCursor"].pop("queryFingerprint")
        with self.assertRaises(RuntimeError):
            sut.validate_cursor_capture(capture, raw, first, current)

    def test_tampered_payload_query_sha_source_metadata_and_first_bytes_are_rejected(self):
        changes = (("query_fingerprint", "c" * 64), ("first_artifact_sha256", "c" * 64),
                   ("private_process_attested_before_and_after", False), ("input_target", "formal"),
                   ("actual_first_rows", []))
        for field, value in changes:
            capture, raw, first, current = valid_capture()
            capture[field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_cursor_capture(capture, raw, first, current)
        for section, field, value in (("query", "pageSize", 2), ("nextCursor", "sourceVersion", "changed"),
                                      ("source_snapshot_before", "sourceSeqTxn", 37),
                                      ("source_snapshot_after", "valid", False),
                                      ("cache_snapshot_after", "physicalTxn", 2)):
            capture, raw, first, current = valid_capture()
            capture[section][field] = value
            with self.subTest(section=section, field=field), self.assertRaises(RuntimeError):
                sut.validate_cursor_capture(capture, raw, first, current)

    def test_payload_projection_order_nullable_contract_and_range_must_match(self):
        for index, value in ((0, "other"), (4, ["trade_date"]), (5, list(reversed(sut.FIELDS))),
                             (6, ["trade_date", "java.time.LocalDate:2026-09-18", "java.time.LocalDate:2026-09-22"]),
                             (3, "[Column[sourceName=trade_date]]")):
            capture, raw, first, current = valid_capture()
            capture["query_fingerprint_parts"][index] = value
            with self.subTest(index=index), self.assertRaises(RuntimeError):
                sut.validate_cursor_capture(capture, raw, first, current)

    def test_changed_current_cache_and_missing_source_metadata_are_rejected(self):
        capture, raw, first, current = valid_capture()
        current = copy.deepcopy(current)
        current["physical"]["table_txn"] += 1
        with self.assertRaises(RuntimeError):
            sut.validate_cursor_capture(capture, raw, first, current)
        capture, raw, first, current = valid_capture()
        del capture["source_snapshot_before"]["mvSeqTxn"]
        with self.assertRaises(RuntimeError):
            sut.validate_cursor_capture(capture, raw, first, current)


class MissingOnlyDdlTests(unittest.TestCase):
    def test_nonowner_model_is_rejected_before_any_query(self):
        model = MagicMock()
        model.get_questdb_schema.return_value = sut.SPEC.model.get_questdb_schema()
        with patch.object(sut, "query") as query, self.assertRaises(RuntimeError):
            sut.ensure_owner_table(model, {"ddl_submissions": []}, Path("unused"), True)
        query.assert_not_called()

    def test_continue_cannot_recreate_missing_table(self):
        with patch.object(sut, "query", return_value=[]), patch.object(sut.fixture, "qwp") as qwp, \
                patch.object(sut.fixture, "save") as save, self.assertRaises(RuntimeError):
            sut.ensure_owner_table(sut.SPEC.model, {"ddl_submissions": []}, Path("unused"), False)
        qwp.assert_not_called()
        save.assert_not_called()

    def test_ddl_unknown_response_retains_durable_intent_and_never_retries(self):
        result, saved = {"ddl_submissions": []}, []
        def execute(sql):
            self.assertEqual("UNKNOWN", saved[-1]["ddl_submissions"][0]["ack"])
            self.assertTrue(sql.startswith("CREATE TABLE " + sut.CACHE))
            raise TimeoutError("unknown private DDL response")
        with patch.object(sut, "query", return_value=[]), \
                patch.object(sut.fixture, "save", side_effect=lambda path, value: saved.append(copy.deepcopy(value))), \
                patch.object(sut.fixture, "qwp", side_effect=execute) as qwp:
            with self.assertRaises(TimeoutError):
                sut.ensure_owner_table(sut.SPEC.model, result, Path("unused"), True)
        qwp.assert_called_once()
        self.assertEqual("UNKNOWN", result["ddl_submissions"][0]["ack"])
        self.assertFalse(result["ddl_submissions"][0]["automatic_retry"])

    def test_existing_exact_owner_model_does_not_execute_ddl(self):
        schema = sut.SPEC.model.get_questdb_schema()
        columns = [{"column": field, "type": kind, "upsertKey": field in schema["dedup_keys"],
                    "designated": field == schema["timestamp_col"]} for field, kind in schema["schema"].items()]
        with patch.object(sut, "query", side_effect=[[{"table_name": sut.CACHE}], columns]), \
                patch.object(sut, "table_state", return_value=cache_snapshot()), \
                patch.object(sut.fixture, "qwp") as qwp, patch.object(sut.fixture, "save") as save:
            self.assertFalse(sut.ensure_owner_table(sut.SPEC.model, {"ddl_submissions": []}, Path("unused"), True)["created"])
        qwp.assert_not_called()
        save.assert_not_called()

    def test_any_prior_success_failure_or_unknown_evidence_blocks_stage_retry(self):
        output = MagicMock(spec=Path)
        output.exists.return_value = False
        sut.reject_existing_attempt(output)
        output.exists.return_value = True
        for content in ("UNKNOWN", "FAILED", "VERIFIED_FIRST_STORED_GENERATIONS"):
            with self.subTest(content=content), self.assertRaises(RuntimeError):
                sut.reject_existing_attempt(output)
        output.write_text.assert_not_called()
        output.read_bytes.assert_not_called()


def recovery_inputs():
    current = {sut.CACHE: cache_snapshot(), sut.COVERAGE: cache_snapshot()}
    for snapshot in current.values():
        snapshot['physical']['table_row_count'] = None
    protected, formal = protected_snapshot(), {'readonly': 'unchanged formal snapshot'}
    identity = {'pid': 31760, 'data_root': str(sut.fixture.ROOT), 'address': '127.0.0.1', 'http_port': 19010, 'pg_port': 18822}
    failed = {'status': 'FAILED', 'error': 'First publication requires empty newly installed owner sample tables; no reset or overwrite allowed',
        'owner_submissions': [], 'ddl_submissions': [{'table': table, 'ack': 'ACKNOWLEDGED', 'automatic_retry': False} for table in sut.MODELS],
        'source_mv_alias_before': protected, 'formal_source_mv_alias_before': formal}
    raw = json.dumps(failed).encode('utf-8')
    audit = {'task_id': 'D100', 'status': 'VERIFIED_EMPTY_TABLES_AFTER_ACKNOWLEDGED_DDL_ONLY',
        'failed_artifact': str(sut.FAILED_FIRST), 'failed_artifact_sha256': hashlib.sha256(raw).hexdigest(),
        'database_mutations': 0, 'failed_evidence_preserved': True, 'source_mv_alias_formal_unchanged': True,
        'stable_physical_and_wal_versions': True, 'private_target_before': identity, 'private_target_after': identity,
        'expected_pid': 31760, 'owner_submissions': [], 'ddl_submissions': failed['ddl_submissions'],
        'tables_before': current, 'tables_after': current, 'source_mv_alias_before': protected,
        'source_mv_alias_after': protected, 'formal_source_mv_alias_before': formal, 'formal_source_mv_alias_after': formal,
        'selected_tables': {table: {'fields': list(fields), 'select_count': 0, 'full_field_selected_count': 0,
            'row_limit': 201, 'records': [], 'raw_physical_row_count': None}
            for table, fields in ((sut.CACHE, sut.FIELDS), (sut.COVERAGE, sut.COVERAGE_FIELDS))}}
    return audit, raw, current, protected, formal, identity


class RecoveryBindingTests(unittest.TestCase):
    def test_explicit_zero_reads_acknowledged_ddl_and_unchanged_snapshots_accept_fresh_evidence_path(self):
        sut.validate_empty_ddl_recovery(*recovery_inputs())
        self.assertNotEqual(sut.FIRST, sut.FAILED_FIRST)
        self.assertEqual('cache-isolated-first-resume-20261006.json', sut.FIRST.name)

    def test_changed_failure_sha_pid_source_formal_table_or_owner_intent_reject_recovery(self):
        for field, value in (('failed_artifact_sha256', 'c' * 64), ('expected_pid', 37904),
                             ('source_mv_alias_after', {}), ('formal_source_mv_alias_after', {}),
                             ('tables_after', {}), ('owner_submissions', [{'ack': 'UNKNOWN'}])):
            inputs = list(recovery_inputs())
            inputs[0][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_empty_ddl_recovery(*inputs)

    def test_count_full_schema_records_and_raw_null_are_required_and_unknown_ddl_never_recovers(self):
        for field, value in (('select_count', 1), ('select_count', False), ('full_field_selected_count', 1),
                             ('fields', ['trade_date']), ('records', [{}]), ('row_limit', 200)):
            inputs = list(recovery_inputs())
            inputs[0]['selected_tables'][sut.CACHE][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_empty_ddl_recovery(*inputs)
        inputs = list(recovery_inputs())
        del inputs[0]['selected_tables'][sut.CACHE]['raw_physical_row_count']
        with self.assertRaises(RuntimeError):
            sut.validate_empty_ddl_recovery(*inputs)
        inputs = list(recovery_inputs())
        failed = json.loads(inputs[1])
        failed['ddl_submissions'][0]['ack'] = 'UNKNOWN'
        inputs[1] = json.dumps(failed).encode('utf-8')
        inputs[0]['failed_artifact_sha256'] = hashlib.sha256(inputs[1]).hexdigest()
        inputs[0]['ddl_submissions'] = failed['ddl_submissions']
        with self.assertRaises(RuntimeError):
            sut.validate_empty_ddl_recovery(*inputs)


class PrivateAttestationTests(unittest.TestCase):
    def setUp(self):
        self.root = sut.fixture.ROOT
        self.marker = {"task_id": "D098", "data_root": str(self.root),
                       "fixture_tables": [sut.fixture.SOURCE, sut.fixture.MV]}
        def read_text(path, **kwargs):
            return ("http.net.bind.to=127.0.0.1:19010\npg.net.bind.to=127.0.0.1:18822\n"
                    if path.name == "server.conf" else json.dumps(self.marker))
        for replacement in (patch.object(Path, "resolve", lambda path, strict=False: path),
                            patch.object(Path, "read_text", read_text),
                            patch.object(sut.fixture.common, "split_windows_command_line", side_effect=json.loads),
                            patch.object(sut.fixture.subprocess, "run")):
            mock = replacement.start()
            self.addCleanup(replacement.stop)
            if replacement.attribute == "run":
                self.run = mock
        self.records = [{"port": port, "address": "127.0.0.1", "pid": 31760, "name": "java.exe",
                         "command": json.dumps(["java.exe", "io.questdb.ServerMain", "-d", str(self.root)])}
                        for port in (19010, 18822)]
        self.set_records(self.records)

    def set_records(self, records):
        self.run.return_value = types.SimpleNamespace(returncode=0, stdout=json.dumps(records))

    def test_actual_dynamic_pid_exact_root_both_loopback_listeners_are_required(self):
        target = sut.fixture.PrivateTarget(self.root, 31760)
        self.assertEqual(31760, target.identity["pid"])
        target.verify()
        self.assertEqual(2, self.run.call_count)

    def test_pid_root_listener_and_process_mismatch_are_rejected_without_http(self):
        for field, value in (("pid", 37904), ("address", "0.0.0.0"), ("name", "powershell.exe"),
                             ("port", 19000), ("command", json.dumps(["java.exe", "-d", str(self.root / "other")]))):
            changed = copy.deepcopy(self.records)
            changed[0][field] = value
            self.set_records(changed)
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.fixture.PrivateTarget(self.root, 31760)

    def test_both_ports_matching_wrong_expected_pid_and_marker_are_rejected(self):
        with self.assertRaises(RuntimeError):
            sut.fixture.PrivateTarget(self.root, 37904)
        self.marker = {"task_id": "D100"}
        with self.assertRaises(RuntimeError):
            sut.fixture.PrivateTarget(self.root, 31760)


if __name__ == "__main__":
    unittest.main(verbosity=2)
