"""Pure D097 script regressions; no CIM, HTTP, PG, ILP or database access."""
from __future__ import annotations

import copy
from datetime import datetime
import importlib
import logging
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
    setattr(log_module, name, logging.getLogger("d097-pure-tests"))
for name in ("debug", "info", "warning", "error", "critical"):
    setattr(log_module, name, getattr(logging.getLogger("d097-pure-tests"), name))
with patch.dict(sys.modules, {"config.logger": log_module}):
    sut = importlib.import_module("accept_d097_cache_isolated")


def real_model_frame(rows=1):
    record = {"trade_date": "20260917", "stock_count": 2, "up_count": 1,
              "down_count": 1, "flat_count": 0, "avg_pct_change": 0.125,
              "total_amount_yi": 0.25, "source_version": "a" * 64}
    return pd.DataFrame([dict(record) for _ in range(rows)])


class D097GuardTests(unittest.TestCase):
    def setUp(self):
        self.target = MagicMock(name="private_attestation")
        self.connection = MagicMock(name="private_pg_connection")
        self.cursor = self.connection.cursor.return_value.__enter__.return_value
        self.cursor.description = [("trade_date", 1114), ("stock_count", 20), ("avg_pct_change", 701)]
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
        self.client = sut.OwnerClient(self.result, TOOLS / "unused-d097-test-output.json")
        self.client.phase = "first"
        self.client.save = MagicMock(name="save")
        self.target.verify.reset_mock()

    def test_query_accepts_original_multiline_select(self):
        sql = sut.fixture.VIEW_SELECTS[sut.SPEC.view].format(
            where="WHERE trade_date >= '2026-09-17' AND trade_date < '2026-09-19'")
        self.assertTrue(sql.startswith("SELECT\n"))
        self.assertEqual([], sut.query(sql))
        self.qwp.assert_called_once_with(sql, isolated=True)

    def test_query_rejects_empty_nonselect_and_semicolon_before_qwp(self):
        for sql in ("", " \n\t", "UPDATE cache SET value=1", "CREATE TABLE x(n INT)",
                    "REFRESH MATERIALIZED VIEW x FULL", "SELECT 1;", "SELECT 1; SELECT 2"):
            with self.subTest(sql=sql), self.assertRaises(RuntimeError):
                sut.query(sql)
        self.qwp.assert_not_called()
        self.target.verify.assert_not_called()

    def test_owner_fetch_accepts_original_multiline_select_and_binds(self):
        sql = sut.fixture.VIEW_SELECTS[sut.SPEC.view].format(
            where="WHERE trade_date >= %s AND trade_date <= %s")
        binds = ("2026-09-17", "2026-09-18")
        frame = self.client.fetch_df(sql, binds)
        self.cursor.execute.assert_called_once_with(sql, binds)
        self.assertEqual(pd.Timestamp("2026-09-17"), frame.iloc[0].trade_date)
        self.assertEqual("int64", str(frame.stock_count.dtype))
        self.assertEqual("float64", str(frame.avg_pct_change.dtype))
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
            "http::addr=127.0.0.1:19000;", auto_flush=False, retry_timeout=0, request_timeout=10000)
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
