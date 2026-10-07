"""Pure D103 preflight guards: no actual DB, native process or owner writes."""
from __future__ import annotations

import copy
from datetime import date, datetime, timezone, timedelta
import importlib
import json
import math
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
sut = importlib.import_module("preflight_d103_equity_style_readonly")


def source_rows(months=("202606", "202607", "202608")):
    result = []
    for month in months:
        for index, code in enumerate(sut.FEATURE_CODES.values()):
            row = {field: None for field in sut.SOURCE_FIELDS}
            row.update(ts_code=code, trade_date=f"{month[:4]}-{month[4:]}-26T00:00:00Z", pct_chg=float(index) - 4.0)
            result.append(row)
    return sorted(result, key=lambda row: (row["trade_date"], row["ts_code"]))


def schema_columns(source=True):
    types = sut.SOURCE_TYPES if source else sut.OUTPUT_TYPES
    timestamp = "trade_date" if source else "month"
    return [{"column": name, "type": kind, "designated": name == timestamp,
             "upsertKey": not source and name == "month"} for name, kind in types.items()]


def state(source=True):
    return {"exists": True, "physical": {"partitionBy": "YEAR", "walEnabled": True, "dedup": not source}}


class SelectAndWindowTests(unittest.TestCase):
    def test_original_multiline_select_allowed(self):
        sut.single_select("\n SELECT ts_code,trade_date,pct_chg\n FROM index_monthly WHERE ts_code IN ('000300.SH')")

    def test_non_select_and_semicolons_refused(self):
        for value in ("", " ", None, "UPDATE index_monthly SET pct_chg=0", "CREATE TABLE x(a long)", "SELECT 1;", "SELECT 1; SELECT 2"):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.single_select(value)

    def test_closed_three_month_window(self):
        months, start, stop = sut.month_window("202606", "202608", date(2026, 10, 6))
        self.assertEqual((months, start, stop), (["202606", "202607", "202608"], "2026-06-01", "2026-09-01"))

    def test_cross_year_and_twelve_month_boundary(self):
        self.assertEqual(sut.month_window("202509", "202608", date(2026, 10, 6))[2], "2026-09-01")

    def test_invalid_reversed_and_overlong_window_refused(self):
        for first, last in (("202600", "202608"), ("202613", "202701"), ("2026-06", "202608"), ("202608", "202606"), ("202508", "202608")):
            with self.subTest(first=first, last=last), self.assertRaises(RuntimeError):
                sut.month_window(first, last, date(2026, 10, 6))

    def test_partial_current_and_future_month_refused(self):
        for last in ("202610", "202611"):
            with self.subTest(last=last), self.assertRaises(RuntimeError):
                sut.month_window("202608", last, date(2026, 10, 6))

    def test_cancel_before_connection(self):
        with patch.object(sut.Path, "exists", return_value=True), patch.object(sut.psycopg2, "connect") as connect:
            with self.assertRaises(RuntimeError):
                sut.ReadOnlyPG("cancelled")
            connect.assert_not_called()

    def test_unsupported_target_before_connection(self):
        with patch.dict(sut.os.environ, {"APP_QUESTDB_HOST": "other-host"}), patch.object(sut.psycopg2, "connect") as connect:
            with self.assertRaises(RuntimeError):
                sut.ReadOnlyPG()
            connect.assert_not_called()

    def test_bool_negative_and_noninteger_counts_refused(self):
        for value in (False, True, -1, 1.0, None, 2**63):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.exact_count(value)


class NativeValueAndSchemaTests(unittest.TestCase):
    def test_native_naive_timestamp_is_explicit_utc(self):
        self.assertEqual(sut.native_value(datetime(2026, 6, 26, 0, 0, 0, 123456)), "2026-06-26T00:00:00.123456Z")

    def test_non_utc_timestamp_and_nonfinite_number_refused(self):
        for value in (datetime(2026, 6, 26, tzinfo=timezone(timedelta(hours=8))), math.inf, math.nan):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.native_value(value)

    def test_business_midnight_and_first_day_guard(self):
        for value in ("2026-06-01T01:00:00Z", "2026-06-26T00:00:00Z", "2026-06-01T00:00:00+08:00", None):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.utc_stamp(value, midnight=True, first_of_month=True)

    def test_complete_source_and_target_schema_accepted(self):
        for source in (True, False):
            model = {"schema": sut.SOURCE_TYPES if source else sut.OUTPUT_TYPES,
                     "timestamp_col": "trade_date" if source else "month"}
            if not source:
                model["dedup_keys"] = ["month"]
            sut.validate_schema(schema_columns(source), state(source), model, source)

    def test_schema_order_type_and_key_drift_refused(self):
        model = {"schema": sut.SOURCE_TYPES, "timestamp_col": "trade_date"}
        variants = []
        reordered = schema_columns(); reordered[0], reordered[1] = reordered[1], reordered[0]; variants.append(reordered)
        changed = schema_columns(); changed[2]["type"] = "FLOAT"; variants.append(changed)
        changed = schema_columns(); changed[0]["upsertKey"] = True; variants.append(changed)
        for columns in variants:
            with self.subTest(columns=columns), self.assertRaises(RuntimeError):
                sut.validate_schema(columns, state(), model, True)

    def test_layout_drift_refused(self):
        model = {"schema": sut.SOURCE_TYPES, "timestamp_col": "trade_date"}
        for field, value in (("partitionBy", "MONTH"), ("walEnabled", False), ("dedup", True)):
            bad = state(); bad["physical"][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_schema(schema_columns(), bad, model, True)

    def test_target_null_metadata_retained_with_actual_empty_proof(self):
        physical = {"id": 7, "directoryName": "equity_style_monthly~7", "table_txn": None,
                    "table_row_count": None, "table_suspended": False, "wal_pending_row_count": 0}
        wal = {"sequencerTxn": 0, "writerTxn": 0, "bufferedTxnSize": 0, "suspended": False}
        reader = MagicMock(); reader.records.side_effect = [[physical], [wal], [{"n": 0}]]
        value = sut.table_state(reader, sut.TARGET)
        self.assertIsNone(value["physical"]["table_txn"])
        self.assertIsNone(value["physical"]["table_row_count"])
        self.assertEqual(value["actual_select_count"], 0)

    def test_source_null_transaction_and_bool_metadata_refused(self):
        for table, txn, count in ((sut.SOURCE, None, None), (sut.TARGET, 1, False)):
            physical = {"id": 7, "directoryName": table + "~7", "table_txn": txn,
                        "table_row_count": count, "table_suspended": False, "wal_pending_row_count": 0}
            wal = {"sequencerTxn": 0, "writerTxn": 0, "bufferedTxnSize": 0, "suspended": False}
            reader = MagicMock(); reader.records.side_effect = [[physical], [wal], [{"n": 0}]]
            with self.subTest(table=table), self.assertRaises(RuntimeError):
                sut.table_state(reader, table)


class CompleteSourceCensusTests(unittest.TestCase):
    def test_complete_three_month_census(self):
        value = sut.source_census(source_rows(), ["202606", "202607", "202608"])
        self.assertEqual((value["rows"], value["field_values"], len(value["coverage"]), value["gaps"]), (48, 672, 48, []))

    def test_missing_code_month_diagnosed(self):
        value = sut.source_census(source_rows()[1:], ["202606", "202607", "202608"])
        self.assertEqual(len(value["gaps"]), 1)
        self.assertEqual(value["gaps"][0]["reason"], "missing_code_month")

    def test_all_null_return_diagnosed_not_zero(self):
        rows = source_rows(); rows[0]["pct_chg"] = None
        value = sut.source_census(rows, ["202606", "202607", "202608"])
        self.assertEqual(value["gaps"][0]["reason"], "all_null_pct_chg")
        self.assertIsNone(value["coverage"][0]["chosen_pct_chg"])

    def test_duplicate_full_key_rejected_even_equal_values(self):
        rows = source_rows(); rows.insert(1, copy.deepcopy(rows[0]))
        with self.assertRaises(RuntimeError):
            sut.source_census(rows, ["202606", "202607", "202608"])

    def test_last_nonnull_chosen_after_later_null(self):
        rows = source_rows(); later = copy.deepcopy(rows[0]); later["trade_date"] = "2026-06-29T00:00:00Z"; later["pct_chg"] = None
        rows.append(later); rows.sort(key=lambda row: (row["trade_date"], row["ts_code"]))
        value = sut.source_census(rows, ["202606", "202607", "202608"])
        chosen = next(item for item in value["coverage"] if item["month"] == "202606" and item["ts_code"] == later["ts_code"])
        self.assertEqual(chosen["chosen_trade_date"], "2026-06-26T00:00:00Z")
        self.assertEqual(chosen["null_pct_chg_rows"], 1)

    def test_bad_codes_dates_fields_and_double_types_refused(self):
        for field, value in (("ts_code", "000920.CSI"), ("trade_date", "2026-06-26T01:00:00Z"), ("pct_chg", True), ("pct_chg", 1), ("pct_chg", math.inf)):
            rows = source_rows(); rows[0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                sut.source_census(rows, ["202606", "202607", "202608"])
        rows = source_rows(); del rows[0]["close"]
        with self.assertRaises(RuntimeError):
            sut.source_census(rows, ["202606", "202607", "202608"])

    def test_outside_month_and_wrong_order_refused(self):
        rows = source_rows(); rows[0]["trade_date"] = "2026-05-26T00:00:00Z"
        with self.assertRaises(RuntimeError):
            sut.source_census(rows, ["202606", "202607", "202608"])
        with self.assertRaises(RuntimeError):
            sut.source_census(list(reversed(source_rows())), ["202606", "202607", "202608"])


class OracleAndEvidenceTests(unittest.TestCase):
    def test_original_ast_oracle_no_reference_import_or_db(self):
        before = set(sys.modules)
        with patch.object(sut.psycopg2, "connect") as connect:
            output, proof = sut.original_oracle(source_rows(), "202606", "202608")
        connect.assert_not_called()
        self.assertEqual(len(output), 3)
        self.assertEqual(list(output[0]), list(sut.OUTPUT_FIELDS))
        self.assertEqual(output[0]["month"], "2026-06-01T00:00:00Z")
        self.assertEqual(output[0]["small_large_ret_1m"], 3.0)
        self.assertEqual(output[0]["growth_value_ret_1m"], 1.0)
        self.assertEqual(proof["original_function_names"], ["_load_index_monthly_returns", "_build_feature_frame"])
        self.assertFalse(any(name.startswith("quant_platform") for name in set(sys.modules) - before))

    def test_exact_binary64_and_null_comparison(self):
        rows, _ = sut.original_oracle(source_rows(), "202606", "202608")
        self.assertTrue(sut.compare_output(copy.deepcopy(rows), rows)["passed"])
        actual = copy.deepcopy(rows); actual[0]["hs300_ret_1m"] = math.nextafter(actual[0]["hs300_ret_1m"], math.inf)
        parity = sut.compare_output(actual, rows)
        self.assertFalse(parity["passed"])
        self.assertEqual(parity["double_tolerance"], 0)
        self.assertNotEqual(parity["differences"][0]["actual_raw_bits"], parity["differences"][0]["expected_raw_bits"])

    def test_missing_extra_and_duplicate_months_refused_or_diagnosed(self):
        rows, _ = sut.original_oracle(source_rows(), "202606", "202608")
        self.assertFalse(sut.compare_output(rows[1:], rows)["passed"])
        with self.assertRaises(RuntimeError):
            sut.compare_output(rows + [copy.deepcopy(rows[0])], rows)

    def test_signed_zero_bits_preserved(self):
        self.assertNotEqual(sut.raw_bits(0.0), sut.raw_bits(-0.0))
        self.assertIsNone(sut.raw_bits(None))

    def test_immutable_new_json_and_jsonl(self):
        with tempfile.TemporaryDirectory(dir=sut.REPO / "var") as directory:
            path = Path(directory) / "new.json"
            sut.save_new(path, {"value": 1})
            with self.assertRaises(FileExistsError):
                sut.save_new(path, {"value": 2})
            self.assertEqual(json.loads(path.read_text())["value"], 1)
            capture = Path(directory) / "source.jsonl"
            proof = sut.save_rows_new(capture, source_rows(), sut.SOURCE_TYPES)
            self.assertEqual(proof["rows"], 48)
            self.assertEqual(proof["sha256"], sut.digest(capture))
            with self.assertRaises(RuntimeError):
                sut.save_rows_new(capture, source_rows(), sut.SOURCE_TYPES)

    def test_output_path_outside_task_and_existing_refused(self):
        with self.assertRaises(RuntimeError):
            sut.output_path(sut.REPO / "var/other.json")
        with patch.object(sut.Path, "exists", return_value=True), self.assertRaises(RuntimeError):
            sut.output_path(sut.DIRECTORY / "old.json")

    def test_row_sentinel_and_nonselect_fail_before_fetch(self):
        reader = object.__new__(sut.ReadOnlyPG); reader.cancel_file = None; reader.connection = MagicMock()
        cursor = reader.connection.cursor.return_value.__enter__.return_value
        cursor.description = [("n",)]; cursor.fetchmany.return_value = [(1,), (2,)]
        with patch.object(sut, "pg_deadline") as deadline:
            deadline.return_value.__enter__.return_value = None
            with self.assertRaises(RuntimeError):
                reader.records("SELECT n FROM x LIMIT 2", cap=1)
            cursor.execute.reset_mock()
            with self.assertRaises(RuntimeError):
                reader.records("DELETE FROM x", cap=1)
            cursor.execute.assert_not_called()

    def test_absolute_pg_deadline_closes_connection(self):
        connection = MagicMock()
        with patch.object(sut.time, "monotonic", side_effect=[1.0, 22.0]), self.assertRaises(RuntimeError):
            with sut.pg_deadline(connection):
                pass
        connection.close.assert_called_once()

    def test_source_version_change_rejected_before_capture_files(self):
        source = source_rows(); expected, _ = sut.original_oracle(source, "202606", "202608")
        native_source = state(); native_target = state(False)
        changed = copy.deepcopy(native_source); changed["changed_txn"] = 1
        reader = MagicMock(); reader.records.side_effect = [schema_columns(), schema_columns(False), source, expected]
        with patch.object(sut, "table_state", side_effect=[native_source, native_target, changed, native_target]), \
             patch.object(sut, "one", return_value={"n": 48}), patch.object(sut, "save_rows_new") as save:
            with self.assertRaises(RuntimeError):
                sut.audit(reader, {}, "202606", "202608", sut.DIRECTORY / "mock.json")
            save.assert_not_called()

    def test_gaps_reject_before_oracle_and_capture(self):
        reader = MagicMock(); reader.records.side_effect = [schema_columns(), schema_columns(False), source_rows()[1:]]
        result = {}
        with patch.object(sut, "table_state", side_effect=[state(), state(False)]), patch.object(sut, "one", return_value={"n": 47}), \
             patch.object(sut, "original_oracle") as oracle, patch.object(sut, "save_rows_new") as save:
            with self.assertRaises(RuntimeError):
                sut.audit(reader, result, "202606", "202608", sut.DIRECTORY / "mock.json")
            self.assertEqual(len(result["source_census"]["gaps"]), 1)
            oracle.assert_not_called(); save.assert_not_called()


if __name__ == "__main__":
    unittest.main()
