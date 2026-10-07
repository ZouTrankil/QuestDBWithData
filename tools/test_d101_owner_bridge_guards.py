"""D101 pure bridge guards; no CIM, socket, PG, ILP or database access."""
from __future__ import annotations

import copy
import hashlib
from datetime import datetime
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch

import pandas as pd

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import d101_etf_cache_owner_bridge as sut


def request(operation="preview"):
    value = {"protocol_version": 1, "operation": operation, "dataset_id": sut.SPEC.dataset_id,
        "trade_date": "2026-09-17", "invocation_id": "f1d2fb13-4647-4be3-897f-654482d1d24a",
        "target": {"private_root": str(sut.fixture.ROOT.resolve()), "expected_pid": 23388,
                   "http_port": 19020, "pg_port": 18832}, "max_source_rows": 50000}
    if operation == "publish":
        value.update(intent_path="intent.json", intent_sha256="a" * 64)
    return value


def frame():
    return pd.DataFrame([{ "trade_date": pd.Timestamp("2026-09-17"), "etf_count": 2,
        "total_share": 1.125, "total_size_yi": 0.25, "source_version": "a" * 64}])


def preview():
    row = sut.audit.canonical(frame().to_dict("records"))[0]
    result = {"protocol_version": 1, "status": "PREVIEW_VERIFIED", "dataset_id": sut.SPEC.dataset_id,
        "trade_date": "2026-09-17", "target": request()["target"],
        "process_attestation": {"pid": 23388}, "target_id": "questdb-" + "b" * 64,
        "source_snapshots": {table: {"id": index} for index, table in enumerate(sut.SPEC.sources)},
        "target_snapshots": {sut.CACHE: {"txn": 0}, sut.COVERAGE: {"txn": 0}},
        "target_actual_row_counts": {sut.CACHE: 0, sut.COVERAGE: 0},
        "original_source_state": {"global": "unchanged"}, "source_version": "a" * 64,
        "raw_source_census": {"all_full_keys": "c" * 64}, "known_source_date": True,
        "expected_cache_records": [row], "expected_receipt": {"trade_date": row["trade_date"],
            "dataset_id": sut.SPEC.dataset_id, "source_version": row["source_version"], "row_count": 1,
            "content_digest": sut.audit._digest(frame(), sut.SPEC.fields)},
        "cache_key_absent_before": True, "existing_cache_records": [], "existing_receipts": [],
        "expected_owner_hits": 0, "expected_owner_misses": 1, "frozen_python_files_sha256": {"owner": "c" * 64},
        "validated_schemas": {"exact": True}, "original_business_sql_sha256": "d" * 64}
    result["sources_fingerprint"] = sut.sha(result["source_snapshots"])
    result["targets_fingerprint"] = sut.sha(result["target_snapshots"])
    result["source_fingerprint"] = sut.sha(sut.semantic_identity(result))
    return result


def submission(job="data.etf_market_overview_daily_cache"):
    frozen_preview = preview()
    intent = {key: frozen_preview[key] for key in ("trade_date", "dataset_id", "target", "target_id",
        "source_fingerprint", "targets_fingerprint", "sources_fingerprint", "expected_cache_records", "expected_receipt")}
    intent.update(protocol_version=1, invocation_id=request()["invocation_id"], preview_path="preview.json",
        preview_sha256="e" * 64, ledger_path="ledger.sqlite3", run_id="run1", slice_id="slice1", revision=4)
    evidence = {"preview_path": intent["preview_path"], "preview_sha256": intent["preview_sha256"]}
    params = {"target_id": intent["target_id"], "source_version": intent["sources_fingerprint"],
              "bootstrap_from": "2026-09-17", "checkpoint_before": "2026-09-16"}
    frozen = {"definition": {"jobId": job, "version": 1, "datasetId": sut.CACHE, "datasetVersion": 1},
        "parameters": params, "mode": "INCREMENTAL", "from": "2026-09-17", "to": "2026-09-18"}
    if job.startswith("write."):
        frozen.update(mode="INGEST", **{"from": None, "to": None})
        frozen["parameters"] = {"groupBatch": "group1", "memberBatch": "member1", "planFingerprint": "f" * 64,
                                "payloadFingerprint": canonical_payload()[1]}
        intent.update(prepared_input_path="prepared.json", prepared_input_sha256="f" * 64)
        evidence.update(prepared_input_path=intent["prepared_input_path"], prepared_input_sha256=intent["prepared_input_sha256"])
    payload = json.dumps({"sourceFingerprint": intent["source_fingerprint"], "returnedRows": 1,
                          "responseEvidence": json.dumps(evidence)})
    entry = {"id": "slice1", "kind": "SLICE", "state": "SUBMITTED", "revision": 4,
             "run_id": "run1", "payload_json": payload}
    event = {"entry_id": "slice1", "state": "SUBMITTED", "revision": 4, "payload_json": payload}
    run = {"id": "run1", "parent_run_id": None, "job_id": job, "job_version": 1, "target_id": intent["target_id"], "frozen_json": json.dumps(frozen)}
    return intent, frozen_preview, entry, run, event


def canonical_payload():
    physical = dict(zip(sut.FIELDS, (pd.Timestamp("2026-09-17").value // 1000, 2, 1.125, 0.25, "a" * 64)))
    line = json.dumps(physical, separators=(",", ":"))
    digest = hashlib.sha256((sut.CACHE + ":1:" + sut.CACHE).encode())
    digest.update(line.encode()); digest.update(b"\n")
    return line, digest.hexdigest()


def caller(params):
    return {"datasetId": sut.CACHE, "memberId": "cache-member", **params, "rows": [{"trade_date": "2026-09-17",
        "etf_count": 2, "total_share": 1.125, "total_size_yi": 0.25, "source_version": "a" * 64}],
        "canonicalPayloadLines": [canonical_payload()[0]]}


class PureBridgeTests(unittest.TestCase):
    def setUp(self):
        self.target = MagicMock(name="private_target")
        self.connection = MagicMock(name="pg_no_socket")
        self.sender_class = MagicMock(name="no_actual_sender")
        self.sender = self.sender_class.from_conf.return_value
        patches = (patch.object(sut.psycopg2, "connect", return_value=self.connection),
            patch.object(sut, "Sender", self.sender_class),
            patch.object(sut.fixture.subprocess, "run", side_effect=AssertionError("No CIM in pure tests")),
            patch.object(sut.fixture, "qwp", side_effect=AssertionError("No HTTP in pure tests")),
            patch.object(sut.fixture, "state", side_effect=AssertionError("No database in pure tests")))
        for replacement in patches:
            replacement.start()
            self.addCleanup(replacement.stop)
        self.result = {"owner_submissions": [], "owner_invoked": False}
        self.persist = MagicMock(name="durable_journal")
        self.client = sut.OwnerClient(self.target, request(), self.result, self.persist)
        self.target.verify.reset_mock()

    def test_exact_request_and_original_multiline_select_allowed(self):
        sut.validate_request(request())
        sut.validate_request(request("publish"))
        sql = sut.audit.common.VIEW_SELECTS[sut.SPEC.view].format(where="WHERE s.timestamp >= %s AND s.timestamp <= %s")
        cursor = self.connection.cursor.return_value.__enter__.return_value
        cursor.description = [("trade_date", 1114), ("etf_count", 20), ("total_share", 701), ("total_size_yi", 701)]
        cursor.fetchmany.return_value = [(datetime(2026, 9, 17), 2, 1.125, 0.25)]
        actual = self.client.fetch_df(sql, ("2026-09-17", "2026-09-17"))
        cursor.execute.assert_called_once_with(sql, ("2026-09-17", "2026-09-17"))
        self.assertEqual(2, actual.iloc[0].etf_count)
        self.sender_class.from_conf.assert_not_called()

    def test_nonselect_semicolon_and_empty_rejected_before_db(self):
        for sql in ("", " \n", "REFRESH MATERIALIZED VIEW x FULL", "UPDATE x SET a=1", "SELECT 1;", "WITH x AS (SELECT 1) SELECT * FROM x"):
            with self.subTest(sql=sql), self.assertRaises(RuntimeError):
                self.client.fetch_df(sql)
        self.connection.cursor.assert_not_called()

    def test_request_rejects_wrong_task_ports_pid_budget_and_day(self):
        changes = [("dataset_id", "retail_sentiment_daily"), ("protocol_version", True), ("trade_date", "20260917"),
                   ("max_source_rows", 50001), ("max_source_rows", True)]
        for field, value in changes:
            modified = request(); modified[field] = value
            with self.subTest(field=field, value=value), self.assertRaises((RuntimeError, ValueError)):
                sut.validate_request(modified)
        for field, value in (("http_port", 19010), ("pg_port", 8812), ("expected_pid", 0), ("expected_pid", True), ("private_root", str(sut.fixture.ROOT.parent))):
            modified = request(); modified["target"][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError): sut.validate_request(modified)

    def test_snapshot_preserves_null_empty_physical_row_count(self):
        fields = ("id", "directoryName", "table_txn", "table_row_count", "table_min_timestamp", "table_max_timestamp",
                  "partitionBy", "designatedTimestamp", "walEnabled", "dedup", "table_suspended", "wal_pending_row_count")
        raw = {"physical": dict.fromkeys(fields), "wal": dict.fromkeys(("sequencerTxn", "writerTxn", "bufferedTxnSize", "suspended"), 0)}
        with patch.object(sut.fixture, "state", return_value=raw):
            self.assertIsNone(sut.snapshot(sut.CACHE)["physical"]["table_row_count"])

    def test_source_snapshot_cannot_use_null_physical_transaction(self):
        fields = ("id", "directoryName", "table_txn", "table_row_count", "table_min_timestamp", "table_max_timestamp",
                  "partitionBy", "designatedTimestamp", "walEnabled", "dedup", "table_suspended", "wal_pending_row_count")
        raw = {"physical": dict.fromkeys(fields), "wal": dict.fromkeys(("sequencerTxn", "writerTxn", "bufferedTxnSize", "suspended"), 0)}
        with patch.object(sut.fixture, "state", return_value=raw), self.assertRaises(RuntimeError):
            sut.snapshot("etf_daily")

    def test_null_target_requires_actual_total_count_zero_and_wal_zero_preserving_null(self):
        states = {table: {"physical": {"table_txn": None, "table_row_count": None},
            "wal": {"sequencerTxn": 0, "writerTxn": 0}} for table in sut.MODELS}
        counts = {table: 0 for table in sut.MODELS}
        sut.validate_target_counts(states, counts)
        self.assertIsNone(states[sut.CACHE]["physical"]["table_txn"])
        self.assertIsNone(states[sut.CACHE]["physical"]["table_row_count"])

    def test_null_target_rejects_boolean_counts_raw_rows_wal_and_nonempty_actual_rows(self):
        for field, value in (("count", False), ("count", 1), ("rows", False), ("rows", 1), ("seq", False), ("seq", 1), ("txn", False)):
            states = {table: {"physical": {"table_txn": None, "table_row_count": None},
                "wal": {"sequencerTxn": 0, "writerTxn": 0}} for table in sut.MODELS}
            counts = {table: 0 for table in sut.MODELS}
            if field == "count": counts[sut.CACHE] = value
            elif field == "rows": states[sut.CACHE]["physical"]["table_row_count"] = value
            elif field == "seq": states[sut.CACHE]["wal"]["sequencerTxn"] = value
            else: states[sut.CACHE]["physical"]["table_txn"] = value
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError): sut.validate_target_counts(states, counts)

    def test_total_target_count_requires_strict_long_and_both_selects(self):
        self.client.records = MagicMock(side_effect=[[{"n": 0}], [{"n": 0}]])
        self.assertEqual({table: 0 for table in sut.MODELS}, sut.actual_target_counts(self.client))
        self.assertEqual([f"SELECT count() AS n FROM {table}" for table in sut.MODELS],
                         [call.args[0] for call in self.client.records.call_args_list])
        for value in (False, None, -1, 0.0):
            self.client.records = MagicMock(return_value=[{"n": value}])
            with self.subTest(value=value), self.assertRaises(RuntimeError): sut.actual_target_counts(self.client)

    def test_actual_target_count_changes_frontier_but_not_stable_unit_fingerprint(self):
        before, after = preview(), preview(); after["target_actual_row_counts"][sut.CACHE] = 1
        self.assertEqual(sut.semantic_identity(before), sut.semantic_identity(after))
        self.assertNotEqual(sut.publication_frontier(before), sut.publication_frontier(after))

    def test_metadata_native_ns_microseconds_epoch_and_null(self):
        expected = pd.Timestamp("2026-09-17")
        self.assertEqual(expected, sut.native_timestamp(1789603200000000000, "TIMESTAMP_NS"))
        self.assertEqual(expected, sut.native_timestamp(1789603200000000, "TIMESTAMP"))
        self.assertEqual(pd.Timestamp("1970-01-01"), sut.native_timestamp(0, "TIMESTAMP"))
        self.assertIs(sut.native_timestamp(None, "TIMESTAMP"), pd.NaT)
        self.assertEqual(123, sut.native_timestamp(1789603200000000123, "TIMESTAMP_NS").nanosecond)

    def test_metadata_native_invalid_kind_boolean_fraction_and_long_sentinel_rejected(self):
        for value, kind in ((True, "TIMESTAMP"), (1.0, "TIMESTAMP_NS"), (1, "LONG"), (None, "LONG"),
                            (-9223372036854775808, "TIMESTAMP_NS"), (9223372036854775808, "TIMESTAMP_NS")):
            with self.subTest(value=value, kind=kind), self.assertRaises(RuntimeError): sut.native_timestamp(value, kind)

    def test_metadata_adapter_is_only_exact_original_tables_select(self):
        rows = [{"table_name": table, "table_min_timestamp": None, "table_max_timestamp": None, "id": index}
                for index, table in enumerate(sut.ALL_MODELS)]
        with patch.object(sut.fixture, "qwp", return_value=rows):
            adapted, carrier = sut.metadata_select("SELECT * FROM tables()")
        self.assertEqual("tables", carrier)
        self.assertEqual("SELECT table_name,cast(table_min_timestamp AS LONG) AS table_min_timestamp,cast(table_max_timestamp AS LONG) AS table_max_timestamp,id FROM tables()", adapted)
        for sql in ("SELECT timestamp FROM etf_daily", "SELECT * FROM tables() WHERE table_name='etf_share'",
                    "SELECT name, numRows, minTimestamp, maxTimestamp, seqTxn FROM table_partitions('etf_daily')"):
            self.assertEqual((sql, None), sut.metadata_select(sql))

    def test_metadata_adapter_missing_or_unrelated_table_rejected(self):
        rows = [{"table_name": table, "table_min_timestamp": None, "table_max_timestamp": None}
                for table in sut.ALL_MODELS]
        for wrong in (rows[:-1], [{**rows[0], "table_name": "unrelated"}, *rows[1:]],
                      [{"table_min_timestamp": None, "table_max_timestamp": None}, *rows[1:]]):
            with patch.object(sut.fixture, "qwp", return_value=wrong), self.assertRaises(RuntimeError):
                sut.metadata_select("SELECT * FROM tables()")

    def test_original_tables_full_projection_decodes_per_table_units_without_changing_other_fields(self):
        tables = list(sut.ALL_MODELS)
        columns = ["table_name", "table_min_timestamp", "table_max_timestamp", "wal_max_timestamp", "id", "table_last_write_timestamp"]
        raw = {"etf_basic": 0, "etf_share": 1789603200000000, "etf_daily": 1789603200000000000,
               sut.CACHE: None, sut.COVERAGE: None}
        wall = datetime(2026, 10, 6, 10, 0)
        records = [(table, raw[table], raw[table], raw[table], index, wall) for index, table in enumerate(tables)]
        metadata = [dict(zip(columns, row)) for row in records]
        cursor = self.connection.cursor.return_value.__enter__.return_value
        cursor.description = [("table_name", 1043), ("table_min_timestamp", 20), ("table_max_timestamp", 20),
                              ("wal_max_timestamp", 20), ("id", 20), ("table_last_write_timestamp", 1114)]
        cursor.fetchmany.return_value = records
        with patch.object(sut.fixture, "qwp", return_value=metadata):
            actual = self.client.fetch_df("SELECT * FROM tables()")
        by_table = actual.set_index("table_name")
        self.assertEqual(pd.Timestamp("2026-09-17"), by_table.loc["etf_daily", "table_min_timestamp"])
        self.assertEqual(by_table.loc["etf_daily", "table_min_timestamp"], by_table.loc["etf_share", "table_min_timestamp"])
        self.assertEqual(pd.Timestamp("1970-01-01"), by_table.loc["etf_basic", "table_min_timestamp"])
        self.assertTrue(pd.isna(by_table.loc[sut.CACHE, "table_min_timestamp"]))
        self.assertEqual(columns, actual.columns.tolist()); self.assertEqual(list(range(5)), actual.id.tolist())
        self.assertEqual(pd.Timestamp("2026-09-17"), by_table.loc["etf_daily", "wal_max_timestamp"])
        self.assertEqual(pd.Timestamp(wall), by_table.loc["etf_daily", "table_last_write_timestamp"])
        self.assertEqual(1, len(self.result["metadata_transport_adaptations"]))

    def test_same_unit_fingerprint_survives_owner_publication_and_hit(self):
        before, after = preview(), preview()
        after.update(target_snapshots={"new": "txn"}, targets_fingerprint="f" * 64, cache_key_absent_before=False,
                     expected_owner_hits=1, expected_owner_misses=0, existing_cache_records=after["expected_cache_records"],
                     existing_receipts=[after["expected_receipt"]])
        self.assertEqual(sut.sha(sut.semantic_identity(before)), sut.sha(sut.semantic_identity(after)))
        self.assertNotEqual(sut.publication_frontier(before), sut.publication_frontier(after))

    def test_generation_source_code_or_expected_values_change_fingerprint(self):
        before = preview()
        for field, value in (("source_version", "f" * 64), ("sources_fingerprint", "f" * 64),
                             ("frozen_python_files_sha256", {"owner": "f" * 64}), ("expected_cache_records", [])):
            after = copy.deepcopy(before); after[field] = value
            with self.subTest(field=field): self.assertNotEqual(sut.sha(sut.semantic_identity(before)), sut.sha(sut.semantic_identity(after)))

    def test_actual_canonical_frozen_parameters_pass(self):
        sut.validate_submission_rows(*submission())

    def test_obsolete_source_parameter_rejected(self):
        args = list(submission()); frozen = json.loads(args[3]["frozen_json"])
        frozen["parameters"]["sources_fingerprint"] = frozen["parameters"].pop("source_version")
        args[3]["frozen_json"] = json.dumps(frozen)
        with self.assertRaises(RuntimeError): sut.validate_submission_rows(*args)

    def test_intent_bool_revision_protocol_and_wrong_preview_rejected(self):
        for field, value in (("revision", True), ("protocol_version", True), ("source_fingerprint", "f" * 64), ("target_id", "questdb-" + "f" * 64)):
            args = list(submission()); args[0][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError): sut.validate_submission_rows(*args)

    def test_sqlite_wrong_state_job_target_event_or_revision_rejected(self):
        for index, field, value in ((2, "state", "VALIDATED"), (2, "revision", 3), (3, "job_id", "data.retail_sentiment_daily_cache"),
                                    (3, "target_id", "wrong"), (4, "entry_id", "other-slice"), (4, "revision", 3)):
            args = list(submission()); args[index][field] = value
            with self.subTest(index=index, field=field), self.assertRaises(RuntimeError): sut.validate_submission_rows(*args)

    def test_source_evidence_sha_and_boolean_unit_count_rejected(self):
        for field, value in (("sourceFingerprint", "f" * 64), ("returnedRows", True),
                            ("responseEvidence", json.dumps({"preview_path": "preview.json", "preview_sha256": "f" * 64}))):
            args = list(submission()); payload = json.loads(args[2]["payload_json"]); payload[field] = value
            args[2]["payload_json"] = args[4]["payload_json"] = json.dumps(payload)
            with self.subTest(field=field), self.assertRaises(RuntimeError): sut.validate_submission_rows(*args)

    def test_reconcile_or_over31days_cannot_publish(self):
        for mode, start, stop in (("RECONCILE", "2026-09-17", "2026-09-18"), ("INCREMENTAL", "2026-09-01", "2026-10-02"),
                                  ("INCREMENTAL", "2026-09-18", "2026-09-19")):
            args = list(submission()); frozen = json.loads(args[3]["frozen_json"])
            frozen.update(mode=mode, **{"from": start, "to": stop}); args[3]["frozen_json"] = json.dumps(frozen)
            with self.subTest(mode=mode, stop=stop), self.assertRaises(RuntimeError): sut.validate_submission_rows(*args)

    def test_prepared_ingest_binds_complete_immutable_caller_rows(self):
        args = submission("write.etf_market_overview_daily_cache")
        params = json.loads(args[3]["frozen_json"])["parameters"]
        with patch.object(sut, "load_json", return_value=(caller(params), "f" * 64)) as load:
            sut.validate_submission_rows(*args)
        load.assert_called_once_with("prepared.json", "f" * 64)

    def test_prepared_wrong_value_batch_or_missing_evidence_rejected(self):
        for change in ("value", "batch", "evidence"):
            args = list(submission("write.etf_market_overview_daily_cache")); params = json.loads(args[3]["frozen_json"])["parameters"]
            value = caller(params)
            if change == "value": value["rows"][0]["total_share"] = 99.0
            elif change == "batch": value["groupBatch"] = "other"
            else: args[0].pop("prepared_input_sha256")
            with self.subTest(change=change), patch.object(sut, "load_json", return_value=(value, "f" * 64)), self.assertRaises(RuntimeError):
                sut.validate_submission_rows(*args)

    def test_prepared_exact_canonical_payload_hash_field_types_and_timestamp(self):
        for change in ("digest", "timestamp", "duplicate", "bool", "nonfinite", "missing", "null"):
            args = list(submission("write.etf_market_overview_daily_cache")); params = json.loads(args[3]["frozen_json"])["parameters"]
            value = caller(params); raw = json.loads(value["canonicalPayloadLines"][0])
            if change == "digest": raw["total_share"] = 99.0
            elif change == "timestamp": raw["trade_date"] += 1
            elif change == "bool": raw["etf_count"] = True
            elif change == "nonfinite": raw["total_size_yi"] = float("inf")
            elif change == "missing": raw.pop("total_share")
            elif change == "null": raw["etf_count"] = None
            value["canonicalPayloadLines"][0] = json.dumps(raw, separators=(",", ":"))
            if change == "duplicate": value["canonicalPayloadLines"][0] = value["canonicalPayloadLines"][0].replace('"etf_count":2', '"etf_count":2,"etf_count":2')
            with self.subTest(change=change), patch.object(sut, "load_json", return_value=(value, "f" * 64)), self.assertRaises(RuntimeError):
                sut.validate_submission_rows(*args)

    def test_admission_rejects_changed_runtime_frontier_or_unknown_anchor(self):
        intent, before, *_ = submission()
        for field, value in (("known_source_date", False), ("expected_owner_hits", 1), ("existing_cache_records", before["expected_cache_records"]),
                             ("target_snapshots", {"txn": 1}), ("process_attestation", {"pid": 999})):
            fresh = copy.deepcopy(before); fresh[field] = value
            with self.subTest(field=field), patch.object(sut, "validate_sqlite_submission") as ledger, self.assertRaises(RuntimeError):
                sut.admit_publication(request("publish"), before, intent, fresh)
            ledger.assert_not_called()

    def test_model_and_single_row_whitelist_reject_before_sender(self):
        impostor = MagicMock(); impostor.get_questdb_schema.return_value = sut.SPEC.model.get_questdb_schema()
        for model, data in ((impostor, frame()), (sut.SPEC.model, frame().iloc[:0]), (sut.SPEC.model, pd.concat([frame(), frame()]))):
            with self.subTest(rows=len(data)), self.assertRaises(RuntimeError): self.client.write_model(model, data)
        self.sender_class.from_conf.assert_not_called(); self.persist.assert_not_called()

    def test_preview_cannot_use_write_model(self):
        with self.assertRaises(RuntimeError): self.client.write_model(sut.SPEC.model, frame())
        self.sender_class.from_conf.assert_not_called()

    def prepare_write(self):
        self.client.preview = preview(); self.client.intent = {"run_id": "run1"}
        self.client.allowed_targets = self.client.preview["target_snapshots"]
        return patch.object(self.client, "require_frozen")

    def test_unknown_flush_durable_before_send_no_retry_close_without_flush(self):
        events, saved = [], []
        self.persist.side_effect = lambda: (events.append("save"), saved.append(copy.deepcopy(self.result)))
        self.sender.flush.side_effect = lambda: (events.append("flush"), (_ for _ in ()).throw(TimeoutError("unknown")))
        self.sender.close.side_effect = lambda **kwargs: events.append(("close", kwargs))
        with self.prepare_write(), self.assertRaises(TimeoutError): self.client.write_model(sut.SPEC.model, frame())
        self.assertEqual(["save", "flush", ("close", {"flush": False})], events)
        submission_row = saved[0]["owner_submissions"][0]
        self.assertEqual("UNKNOWN", submission_row["ack"]); self.assertEqual(1, submission_row["planned_rows"])
        self.assertEqual(1, submission_row["attempted_rows"]); self.assertEqual(0, submission_row["acknowledged_rows"])
        self.assertFalse(submission_row["automatic_retry"])
        self.sender.flush.assert_called_once(); self.sender_class.from_conf.assert_called_once()
        self.assertEqual(0, self.client.active_senders)

    def test_sender_close_exception_cannot_claim_stopped(self):
        self.sender.flush.side_effect = TimeoutError("unknown")
        self.sender.close.side_effect = RuntimeError("close failed")
        with self.prepare_write(), self.assertRaises(RuntimeError): self.client.write_model(sut.SPEC.model, frame())
        self.assertTrue(self.client.sender_close_failed)
        self.assertEqual("UNKNOWN", self.result["owner_submissions"][0]["ack"])

    def test_source_change_between_buffer_and_flush_blocks_flush(self):
        with self.prepare_write() as guard:
            guard.side_effect = [None, RuntimeError("source changed")]
            with self.assertRaises(RuntimeError): self.client.write_model(sut.SPEC.model, frame())
        self.sender.flush.assert_not_called(); self.sender.close.assert_called_once_with(flush=False)
        self.assertEqual("UNKNOWN", self.result["owner_submissions"][0]["ack"])

    def test_wrong_prepared_value_rejected_before_journal_or_send(self):
        with self.prepare_write():
            changed = frame(); changed.loc[0, "etf_count"] = 3
            with self.assertRaises(RuntimeError): self.client.write_model(sut.SPEC.model, changed)
        self.persist.assert_not_called(); self.sender_class.from_conf.assert_not_called()

    def test_require_frozen_rechecks_cim_source_target_owner_and_ledger(self):
        self.client.preview = preview(); self.client.intent = {"run_id": "run1"}; self.client.allowed_targets = self.client.preview["target_snapshots"]
        self.client.allowed_target_counts = self.client.preview["target_actual_row_counts"]
        with patch.object(sut, "snapshots", side_effect=[self.client.preview["source_snapshots"], self.client.allowed_targets]), \
             patch.object(sut, "owner_files", return_value=self.client.preview["frozen_python_files_sha256"]), \
             patch.object(sut, "actual_target_counts", return_value=self.client.allowed_target_counts), \
             patch.object(sut, "validate_sqlite_submission") as ledger:
            self.client.require_frozen()
        self.target.verify.assert_called_once(); ledger.assert_called_once_with(self.client.intent, self.client.preview)

    def test_duplicate_json_fields_rejected(self):
        with self.assertRaises(RuntimeError): sut._unique_object([("a", 1), ("a", 2)])

    def test_real_sqlite_readonly_binding_closes_connection(self):
        args = submission(); intent = args[0]
        intent["ledger_path"] = str(sut.audit.common.REPO_ROOT / "var/mock-only-ledger.sqlite3")
        database = MagicMock(); database.execute.return_value.fetchone.side_effect = [args[2], args[3], args[4], [0]]
        with patch.object(sut.Path, "resolve", autospec=True, side_effect=lambda value, **_: value), \
             patch.object(sut.sqlite3, "connect", return_value=database) as connect:
            sut.validate_sqlite_submission(intent, args[1])
        self.assertIn("?mode=ro", connect.call_args.args[0])
        database.close.assert_called_once()
        self.assertTrue(all(call.args[0].startswith("SELECT") for call in database.execute.call_args_list))

    def test_cancelled_sqlite_run_rejects_and_closes_before_owner_submission(self):
        args = submission(); intent = args[0]
        intent["ledger_path"] = str(sut.audit.common.REPO_ROOT / "var/mock-only-ledger.sqlite3")
        database = MagicMock(); database.execute.return_value.fetchone.side_effect = [args[2], args[3], args[4], [1]]
        with patch.object(sut.Path, "resolve", autospec=True, side_effect=lambda value, **_: value), \
             patch.object(sut.sqlite3, "connect", return_value=database), self.assertRaises(RuntimeError):
            sut.validate_sqlite_submission(intent, args[1])
        database.close.assert_called_once(); self.sender_class.from_conf.assert_not_called()

    def test_parent_cancellation_rejects_before_sender(self):
        database = MagicMock(); database.execute.return_value.fetchone.side_effect = [
            [0], {"id": "parent1", "parent_run_id": None}, [1]]
        with self.assertRaises(RuntimeError): sut.require_uncancelled_lineage(database, {"id": "child1", "parent_run_id": "parent1"})
        self.assertEqual(("parent1",), database.execute.call_args.args[1]); self.sender_class.from_conf.assert_not_called()

    def test_complete_uncancelled_actual_parent_chain_passes(self):
        database = MagicMock(); database.execute.return_value.fetchone.side_effect = [
            [0], {"id": "parent1", "parent_run_id": "root1"}, [0], {"id": "root1", "parent_run_id": None}, [0]]
        sut.require_uncancelled_lineage(database, {"id": "child1", "parent_run_id": "parent1"})
        self.assertEqual(5, database.execute.call_count)

    def test_cyclic_or_missing_actual_parent_chain_rejects(self):
        for parent in (None, {"id": "child1", "parent_run_id": "child1"}, {"id": "other", "parent_run_id": None}):
            database = MagicMock(); database.execute.return_value.fetchone.side_effect = [[0], parent]
            with self.subTest(parent=parent), self.assertRaises(RuntimeError):
                sut.require_uncancelled_lineage(database, {"id": "child1", "parent_run_id": "parent1"})

    def test_existing_invocation_claim_blocks_automatic_owner_retry(self):
        intent, frozen, *_ = submission()
        with patch.object(sut.fixture, "PrivateTarget", return_value=self.target), \
             patch.object(sut, "OwnerClient", return_value=self.client), \
             patch.object(sut, "build_preview", return_value=frozen), \
             patch.object(sut, "load_json", side_effect=[(intent, "a" * 64), (frozen, "e" * 64)]), \
             patch.object(sut, "admit_publication"), \
             patch.object(sut.fixture, "save_new", side_effect=FileExistsError("durable original invocation exists")), \
             patch.object(sut.audit.MarketBarometerReadThroughCache, "read") as read, self.assertRaises(FileExistsError):
            sut.execute(request("publish"), self.result, self.persist)
        read.assert_not_called(); self.sender_class.from_conf.assert_not_called(); self.assertFalse(self.result["owner_invoked"])

    def test_empty_generation_point_read_proves_key_absence(self):
        self.client.records = MagicMock(side_effect=[[], []])
        self.assertEqual(([], []), sut.point_records(self.client, "2026-09-17", "a" * 64))
        self.assertEqual(2, self.client.records.call_count)
        self.assertIn("LIMIT 2", self.client.records.call_args_list[0].args[0])

    def test_duplicate_complete_generation_key_rejected(self):
        self.client.records = MagicMock(side_effect=[[preview()["expected_cache_records"][0]] * 2, []])
        with self.assertRaises(RuntimeError): sut.point_records(self.client, "2026-09-17", "a" * 64)

    def test_execute_preview_never_calls_original_read_install_or_sender(self):
        self.client.sender_close_failed = False
        with patch.object(sut.fixture, "PrivateTarget", return_value=self.target), \
             patch.object(sut, "OwnerClient", return_value=self.client), \
             patch.object(sut, "build_preview", return_value=preview()), \
             patch.object(sut.audit.MarketBarometerReadThroughCache, "read") as read, \
             patch.object(sut.audit.MarketBarometerReadThroughCache, "install") as install:
            sut.execute(request(), self.result, self.persist)
        read.assert_not_called(); install.assert_not_called(); self.sender_class.from_conf.assert_not_called()
        self.assertFalse(self.result["owner_invoked"]); self.assertEqual("PREVIEW_VERIFIED", self.result["status"])
        self.connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main(verbosity=2)
