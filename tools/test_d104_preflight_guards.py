"""Pure D104 source/oracle guards. No actual DB, process, publisher or provider."""
from __future__ import annotations

import copy
from datetime import date, datetime, timezone
import json
import math
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import preflight_d104_macro_core_readonly as sut

MONTHS = ["202606", "202607", "202608"]


def models():
    return {table: sut.model_schema(table) for table in sut.TABLE_CLASSES}


def stamp(month):
    return f"{month[:4]}-{month[4:]}-01T00:00:00Z"


def sample():
    schemas = models()
    rows = {}
    for table in sut.SOURCES:
        months = ["202506", "202507", "202508", "202509", "202510", "202511", "202512", "202601", "202602", "202603", "202604", "202605", *MONTHS] if table == "sf_month" else MONTHS
        if table == "cn_gdp":
            months = ["202606"]
        rows[table] = []
        for index, month in enumerate(months):
            row = {field: 1.0 if kind == "DOUBLE" else None for field, kind in schemas[table]["schema"].items()}
            row[schemas[table]["timestamp_col"]] = stamp(month)
            if table == "cn_gdp":
                row.update(quarter="2026Q2", report_date="2026-06-30T00:00:00Z", gdp_yoy=4.7)
            if table == "sf_month":
                row["stk_endval"] = float(100 + index) if index < 12 else float(200 + (index - 12) * 20)
                row["inc_month"] = float(30000 + index)
            rows[table].append(row)
    return rows


def columns(model):
    return [{"column": name, "type": kind, "designated": name == model["timestamp_col"], "upsertKey": name in model["dedup_keys"]} for name, kind in model["schema"].items()]


def native_state(model):
    return {"exists": True, "physical": {"partitionBy": "YEAR", "walEnabled": True, "dedup": True, "designatedTimestamp": model["timestamp_col"]}}


class SelectWindowTests(unittest.TestCase):
    def test_multiline_select_allowed(self):
        sut.single_select("\n SELECT month,inc_month FROM sf_month\n WHERE month<%s ORDER BY month DESC LIMIT 12")

    def test_non_select_empty_and_semicolon_refused(self):
        for value in (None, "", " ", "INSERT INTO sf_month VALUES(1)", "CREATE TABLE t(x long)", "SELECT 1;", "SELECT 1; SELECT 2"):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.single_select(value)

    def test_closed_three_months(self):
        self.assertEqual(sut.month_window("202606", "202608", date(2026, 10, 7)), (MONTHS, "2026-06-01", "2026-09-01"))

    def test_twelve_month_boundary_and_cross_year(self):
        self.assertEqual(len(sut.month_window("202509", "202608", date(2026, 10, 7))[0]), 12)

    def test_current_future_invalid_reversed_long_refused(self):
        for first, last in (("202608", "202610"), ("202608", "202611"), ("202600", "202608"), ("202613", "202701"), ("202608", "202606"), ("202508", "202608")):
            with self.subTest(first=first, last=last), self.assertRaises(RuntimeError):
                sut.month_window(first, last, date(2026, 10, 7))

    def test_cancel_before_connection(self):
        with patch.object(sut.transport.Path, "exists", return_value=True), patch.object(sut.transport.psycopg2, "connect") as connect:
            with self.assertRaises(RuntimeError):
                sut.ReadOnlyPG("cancelled")
            connect.assert_not_called()

    def test_unsupported_host_before_connection(self):
        with patch.dict(sut.transport.os.environ, {"APP_QUESTDB_HOST": "external"}), patch.object(sut.transport.psycopg2, "connect") as connect:
            with self.assertRaises(RuntimeError):
                sut.ReadOnlyPG()
            connect.assert_not_called()

    def test_bool_negative_noninteger_count_refused(self):
        for value in (True, False, -1, 1.0, None, 2**63):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.exact_count(value)

    def test_execution_deadline_closes_connection(self):
        connection = MagicMock()
        with patch.object(sut.transport.time, "monotonic", side_effect=[0, 21]), self.assertRaises(RuntimeError):
            with sut.transport.pg_deadline(connection):
                pass
        connection.close.assert_called_once()


class SchemaIdentityTests(unittest.TestCase):
    def test_original_dynamic_and_literal_full_schemas(self):
        schemas = models()
        self.assertEqual([len(schemas[table]["schema"]) for table in sut.TABLE_CLASSES], [13, 31, 60, 10, 10, 4, 9])
        for model in schemas.values():
            sut.validate_schema(columns(model), native_state(model), model)

    def test_unknown_model_and_native_table_refused(self):
        with self.assertRaises(RuntimeError):
            sut.model_schema("other")
        reader = MagicMock()
        with self.assertRaises(RuntimeError):
            sut.table_state(reader, "other")
        reader.records.assert_not_called()

    def test_column_order_type_designated_key_refused(self):
        model = models()["cn_cpi"]
        variants = []
        value = columns(model); value[0], value[1] = value[1], value[0]; variants.append(value)
        value = columns(model); value[1]["type"] = "FLOAT"; variants.append(value)
        value = columns(model); value[0]["designated"] = False; variants.append(value)
        value = columns(model); value[0]["upsertKey"] = False; variants.append(value)
        value = columns(model); value[1]["designated"] = 0; variants.append(value)
        for value in variants:
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.validate_schema(value, native_state(model), model)

    def test_partition_wal_dedup_designated_drift_refused(self):
        model = models()["cn_gdp"]
        for field, value in (("partitionBy", "MONTH"), ("walEnabled", False), ("dedup", False), ("designatedTimestamp", "month")):
            state = native_state(model); state["physical"][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.validate_schema(columns(model), state, model)

    def test_null_metadata_only_actual_empty_target(self):
        p = {"id": 5, "directoryName": "macro_core_monthly~5", "table_txn": None, "table_row_count": None, "table_suspended": False, "wal_pending_row_count": 0}
        w = {"sequencerTxn": 0, "writerTxn": 0, "bufferedTxnSize": 0, "suspended": False}
        reader = MagicMock(); reader.records.side_effect = [[p], [w], [{"n": 0, "min_timestamp": None, "max_timestamp": None}]]
        state = sut.table_state(reader, sut.TARGET)
        self.assertIsNone(state["physical"]["table_txn"])
        self.assertEqual(state["actual_select_count"], 0)

    def test_source_null_metadata_refused(self):
        p = {"id": 5, "directoryName": "cn_cpi~5", "table_txn": None, "table_row_count": None, "table_suspended": False, "wal_pending_row_count": 0}
        w = {"sequencerTxn": 0, "writerTxn": 0, "bufferedTxnSize": 0, "suspended": False}
        reader = MagicMock(); reader.records.side_effect = [[p], [w], [{"n": 0, "min_timestamp": None, "max_timestamp": None}]]
        with self.assertRaises(RuntimeError):
            sut.table_state(reader, "cn_cpi")

    def test_missing_required_source_refused(self):
        reader = MagicMock(); reader.records.return_value = []
        with self.assertRaises(RuntimeError):
            sut.table_state(reader, "cn_m")

    def test_absent_formal_output_retained(self):
        reader = MagicMock(); reader.records.return_value = []
        self.assertEqual(sut.table_state(reader, sut.TARGET), {"exists": False})


class RawSourceCensusTests(unittest.TestCase):
    def test_real_shape_28_rows_full412_fields(self):
        census = sut.source_census(sample(), models(), MONTHS)
        self.assertEqual((census["rows"], census["field_values"], census["double_slots"]), (28, 412, 383))
        self.assertEqual(census["required_component_gaps"], [])
        self.assertEqual(census["sf_prior_observation_months"], ["202506", "202507", "202508", "202509", "202510", "202511", "202512", "202601", "202602", "202603", "202604", "202605"])

    def test_duplicate_full_and_normalized_month_refused(self):
        value = sample(); value["sf_month"].insert(0, copy.deepcopy(value["sf_month"][0]))
        with self.assertRaises(RuntimeError):
            sut.source_census(value, models(), MONTHS)
        value = sample(); later = copy.deepcopy(value["cn_gdp"][0]); later["report_date"] = "2026-06-29T00:00:00Z"; value["cn_gdp"].insert(0, later)
        with self.assertRaises(RuntimeError):
            sut.source_census(value, models(), MONTHS)

    def test_null_required_and_missing_month_diagnosed(self):
        value = sample(); value["cn_cpi"][0]["nt_yoy"] = None; value["cn_ppi"].pop()
        gaps = sut.source_census(value, models(), MONTHS)["required_component_gaps"]
        self.assertEqual({item["reason"] for item in gaps}, {"null_required_component", "missing_required_month"})

    def test_unrelated_nullable_fields_preserved(self):
        value = sample(); value["cn_pmi"][0]["pmi010100"] = None; value["cn_cpi"][0]["cnt_val"] = None
        self.assertEqual(sut.source_census(value, models(), MONTHS)["required_component_gaps"], [])

    def test_bad_double_bool_int_nonfinite_refused(self):
        for wrong in (True, 1, math.nan, math.inf):
            value = sample(); value["cn_m"][0]["m2_yoy"] = wrong
            with self.subTest(wrong=wrong), self.assertRaises(RuntimeError):
                sut.source_census(value, models(), MONTHS)

    def test_bad_timestamp_month_carrier_and_quarter_refused(self):
        for table, field, wrong in (("cn_m", "month", "2026-06-02T00:00:00Z"), ("cn_m", "month", "2026-06-01T01:00:00Z"), ("cn_gdp", "quarter", "2026Q1"), ("cn_gdp", "report_date", None)):
            value = sample(); value[table][0][field] = wrong
            with self.subTest(table=table, field=field), self.assertRaises(RuntimeError):
                sut.source_census(value, models(), MONTHS)

    def test_fullfield_order_and_chronological_order_refused(self):
        value = sample(); value["cn_cpi"][0] = dict(reversed(list(value["cn_cpi"][0].items())))
        with self.assertRaises(RuntimeError):
            sut.source_census(value, models(), MONTHS)
        value = sample(); value["cn_ppi"].reverse()
        with self.assertRaises(RuntimeError):
            sut.source_census(value, models(), MONTHS)

    def test_short_sf_prior_context_refused(self):
        value = sample(); value["sf_month"].pop(0)
        with self.assertRaises(RuntimeError):
            sut.source_census(value, models(), MONTHS)

    def test_sf_calendar_gap_diagnosed_not_filled(self):
        value = sample(); value["sf_month"][0]["month"] = "2025-05-01T00:00:00Z"
        census = sut.source_census(value, models(), MONTHS)
        self.assertEqual(census["sf_context_calendar_gaps"], [{"after": "202505", "before": "202507"}])
        output, _ = sut.original_oracle(value, MONTHS)
        self.assertEqual(output[0]["social_financing_yoy"], 1.0)

    def test_outside_window_refused(self):
        value = sample(); value["cn_cpi"][0]["month"] = "2026-05-01T00:00:00Z"
        with self.assertRaises(RuntimeError):
            sut.source_census(value, models(), MONTHS)


class OriginalOracleTests(unittest.TestCase):
    def test_original_ast_no_imports_no_connection(self):
        loaded = set(sys.modules)
        with patch.object(sut.transport.psycopg2, "connect") as connect:
            output, proof = sut.original_oracle(sample(), MONTHS)
        connect.assert_not_called()
        self.assertFalse(any(name.startswith("quant_platform") for name in set(sys.modules) - loaded))
        self.assertFalse(proof["public_builder_invoked"])
        self.assertEqual(len(output), 3)
        self.assertEqual(list(output[0]), list(sut.OUTPUT_FIELDS))

    def test_gdp_only_own_month_no_fill(self):
        output, _ = sut.original_oracle(sample(), MONTHS)
        self.assertEqual([row["gdp_yoy"] for row in output], [4.7, None, None])

    def test_sf_12_observation_ratio_unscaled(self):
        output, _ = sut.original_oracle(sample(), MONTHS)
        self.assertEqual([row["social_financing_yoy"] for row in output], [200.0 / 100.0 - 1, 220.0 / 101.0 - 1, 240.0 / 102.0 - 1])

    def test_social_increment_legacy_name_exact(self):
        output, _ = sut.original_oracle(sample(), MONTHS)
        self.assertEqual(output[0]["new_rmb_loan"], 30012.0)
        self.assertEqual(output[0]["social_financing_stock"], 200.0)

    def test_required_null_withheld_not_empty_success(self):
        value = sample(); value["cn_pmi"][1]["pmi010000"] = None
        output, _ = sut.original_oracle(value, MONTHS)
        self.assertEqual([sut.month_key(row["month"]) for row in output], ["202606", "202608"])

    def test_nullable_gdp_and_sf_yoy_do_not_withhold(self):
        value = sample(); value["cn_gdp"][0]["gdp_yoy"] = None; value["sf_month"][0]["stk_endval"] = None
        output, _ = sut.original_oracle(value, MONTHS)
        self.assertEqual(len(output), 3)
        self.assertIsNone(output[0]["gdp_yoy"])
        self.assertIsNone(output[0]["social_financing_yoy"])

    def test_full_nullable_bits_exact_and_one_ulp_difference(self):
        output, _ = sut.original_oracle(sample(), MONTHS)
        self.assertTrue(sut.compare_output(copy.deepcopy(output), output)["passed"])
        actual = copy.deepcopy(output); actual[0]["social_financing_yoy"] = math.nextafter(actual[0]["social_financing_yoy"], math.inf)
        parity = sut.compare_output(actual, output)
        self.assertFalse(parity["passed"])
        self.assertEqual(parity["double_tolerance"], 0)
        self.assertNotEqual(parity["differences"][0]["actual_raw_bits"], parity["differences"][0]["expected_raw_bits"])

    def test_missing_extra_duplicate_output_month(self):
        output, _ = sut.original_oracle(sample(), MONTHS)
        self.assertFalse(sut.compare_output(output[:2], output)["passed"])
        with self.assertRaises(RuntimeError):
            sut.compare_output(output + [copy.deepcopy(output[0])], output)


class CaptureEvidenceTests(unittest.TestCase):
    def test_unknown_capture_target_before_select(self):
        reader = MagicMock()
        with self.assertRaises(RuntimeError):
            sut.capture_source(reader, "other", models()["cn_cpi"], "2026-06-01", "2026-09-01")
        reader.records.assert_not_called()

    def test_prior_context_limit12_does_not_probe_older13th(self):
        rows = sample()["sf_month"]
        reader = MagicMock(); reader.records.side_effect = [[{"n": 3}], rows[12:], list(reversed(rows[:12]))]
        captured, queries = sut.capture_source(reader, "sf_month", models()["sf_month"], "2026-06-01", "2026-09-01")
        self.assertEqual(captured, rows)
        self.assertTrue(queries[1]["sql"].endswith("DESC LIMIT 12"))
        self.assertEqual(reader.records.call_count, 3)

    def test_window_count_exceeds_cap_refused_before_source_read(self):
        reader = MagicMock(); reader.records.return_value = [{"n": 13}]
        with self.assertRaises(RuntimeError):
            sut.capture_source(reader, "cn_cpi", models()["cn_cpi"], "2026-06-01", "2026-09-01")
        self.assertEqual(reader.records.call_count, 1)

    def test_truncated_source_vs_actual_count_refused(self):
        reader = MagicMock(); reader.records.side_effect = [[{"n": 3}], sample()["cn_cpi"][:2]]
        with self.assertRaises(RuntimeError):
            sut.capture_source(reader, "cn_cpi", models()["cn_cpi"], "2026-06-01", "2026-09-01")

    def test_new_only_json_jsonl_and_raw_bits(self):
        with tempfile.TemporaryDirectory(dir=sut.REPO / "var") as directory:
            path = Path(directory) / "new.json"
            sut.save_new(path, {"count": 1})
            with self.assertRaises(FileExistsError):
                sut.save_new(path, {"count": 2})
            capture = Path(directory) / "source.jsonl"
            proof = sut.save_rows_new(capture, sample()["sf_month"], models()["sf_month"]["schema"])
            self.assertEqual((proof["rows"], proof["field_values"]), (15, 60))
            data = json.loads(capture.read_text().splitlines()[0])
            self.assertEqual(data["raw_double_bits"]["stk_endval"], sut.raw_bits(100.0))
            with self.assertRaises(RuntimeError):
                sut.save_rows_new(capture, [], models()["sf_month"]["schema"])

    def test_evidence_path_scope_no_overwrite(self):
        with self.assertRaises(RuntimeError):
            sut.output_path(sut.REPO / "outside.json")
        with patch.object(sut.Path, "exists", return_value=True), self.assertRaises(RuntimeError):
            sut.output_path(sut.DIRECTORY / "existing.json")

    def test_signed_zero_and_explicit_null(self):
        self.assertNotEqual(sut.raw_bits(0.0), sut.raw_bits(-0.0))
        self.assertIsNone(sut.raw_bits(None))

    def test_source_version_change_rejected_before_capture_output(self):
        schemas = models(); rows = sample(); output, oracle = sut.original_oracle(rows, MONTHS)
        states = {table: native_state(model) for table, model in schemas.items()}
        changed = copy.deepcopy(states); changed["cn_cpi"]["physical"]["table_txn"] = 99
        reader = MagicMock(); reader.records.side_effect = [*(columns(model) for model in schemas.values()), output, *(columns(model) for model in schemas.values())]
        with patch.object(sut, "table_state", side_effect=[*states.values(), *changed.values()]), \
             patch.object(sut, "capture_source", side_effect=[(rows[table], []) for table in sut.SOURCES]), \
             patch.object(sut, "save_rows_new") as save:
            with self.assertRaises(RuntimeError):
                sut.audit(reader, {}, "202606", "202608", sut.DIRECTORY / "mock.json")
            save.assert_not_called()

    def test_source_schema_change_rejected_before_capture_output(self):
        schemas = models(); rows = sample(); output, oracle = sut.original_oracle(rows, MONTHS)
        states = {table: native_state(model) for table, model in schemas.items()}
        native_columns = [columns(model) for model in schemas.values()]
        changed = copy.deepcopy(native_columns); changed[0][1]["type"] = "FLOAT"
        reader = MagicMock(); reader.records.side_effect = [*native_columns, output, *changed]
        with patch.object(sut, "table_state", side_effect=[*states.values(), *states.values()]), \
             patch.object(sut, "capture_source", side_effect=[(rows[table], []) for table in sut.SOURCES]), \
             patch.object(sut, "save_rows_new") as save:
            with self.assertRaises(RuntimeError):
                sut.audit(reader, {}, "202606", "202608", sut.DIRECTORY / "mock.json")
            save.assert_not_called()


if __name__ == "__main__":
    unittest.main()
