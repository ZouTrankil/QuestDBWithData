"""Pure D103 source fixture tests: mock every DB, HTTP and native operation."""
from __future__ import annotations

import copy
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
sut = importlib.import_module("prepare_d103_equity_style_isolated")


def rows(months=sut.MONTHS):
    values = []
    for month in months:
        for index, code in enumerate(sut.audit.FEATURE_CODES.values()):
            row = {field: None for field in sut.audit.SOURCE_FIELDS}
            row.update(ts_code=code, trade_date=f"{month[:4]}-{month[4:]}-26T00:00:00Z", pct_chg=float(index), layer="macro_core", bucket="broad_base")
            values.append(row)
    return sorted(values, key=lambda row: (row["trade_date"], row["ts_code"]))


def startup():
    return {"task_id": "D103", "pid": 41560, "data_root": str(sut.ROOT.resolve()), "http_port": 19030, "pg_port": 18842,
            "birth_utc": "2026-10-06T14:15:29.5834980Z", "arguments": ["-m", "io.questdb/io.questdb.ServerMain", "-d", str(sut.ROOT.resolve())]}


def native():
    return [{"port": port, "address": "127.0.0.1", "pid": 41560, "name": "java.exe", "command": "mock native argv", "birth": startup()["birth_utc"]} for port in (19030, 18842)]


def journal(path):
    result = {"invocation_id": "pure-owned-claim", "operations": [], "status": "IN_PROGRESS"}
    sut.audit.save_new(path, result)
    return result


class PrivateIdentityTests(unittest.TestCase):
    def test_startup_exact_root_pid_ports(self):
        self.assertEqual(sut.validate_startup(startup(), sut.ROOT.resolve(), 41560), startup()["birth_utc"])

    def test_startup_wrong_task_root_pid_and_ports_refused(self):
        for key, value in (("task_id", "D101"), ("pid", 23388), ("data_root", str(sut.REPO / "var/other")), ("http_port", 9000), ("pg_port", 8812)):
            proof = startup(); proof[key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                sut.validate_startup(proof, sut.ROOT.resolve(), 41560)

    def test_startup_multiple_roots_or_missing_module_refused(self):
        for argv in (["-d", str(sut.ROOT.resolve())], ["io.questdb/io.questdb.ServerMain", "-d", str(sut.ROOT.resolve()), "-d", str(sut.ROOT.resolve())]):
            proof = startup(); proof["arguments"] = argv
            with self.subTest(argv=argv), self.assertRaises(RuntimeError):
                sut.validate_startup(proof, sut.ROOT.resolve(), 41560)

    def test_native_two_listeners_exact_birth(self):
        with patch.object(sut, "split_windows_command_line", return_value=startup()["arguments"]):
            value = sut.validate_listener_records(native(), sut.ROOT.resolve(), 41560, startup()["birth_utc"])
        self.assertEqual(value["pg_port"], 18842)

    def test_native_pid_root_port_and_birth_changes_refused(self):
        for key, value in (("pid", 1), ("port", 8812), ("birth", "2026-10-06T14:15:30.5834980Z"), ("address", "0.0.0.0"), ("name", "python.exe")):
            proof = native(); proof[0][key] = value
            with self.subTest(key=key), patch.object(sut, "split_windows_command_line", return_value=startup()["arguments"]), self.assertRaises(RuntimeError):
                sut.validate_listener_records(proof, sut.ROOT.resolve(), 41560, startup()["birth_utc"])
        with patch.object(sut, "split_windows_command_line", return_value=["io.questdb/io.questdb.ServerMain", "-d", str(sut.REPO / "var/other")]), self.assertRaises(RuntimeError):
            sut.validate_listener_records(native(), sut.ROOT.resolve(), 41560, startup()["birth_utc"])

    def test_unknown_and_non_utc_birth_refused(self):
        for value in (None, "UNKNOWN", "2026-10-06T22:15:29.5834980+08:00"):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                sut.normalize_birth(value)

    def test_birth_seventh_fraction_digit_not_lost(self):
        self.assertNotEqual(sut.normalize_birth("2026-10-06T14:15:29.5834981Z"), sut.normalize_birth("2026-10-06T14:15:29.5834982Z"))


class SourceCaptureAndMutationTests(unittest.TestCase):
    def test_single_initial_insert_contains_complete14_fields(self):
        source = rows(sut.INITIAL_MONTHS)
        sql = sut.insert_sql(source)
        sut.validate_write("SOURCE_INITIAL_INSERT", sql, 32, source)
        self.assertTrue(sql.startswith("INSERT INTO index_monthly (" + ",".join(sut.audit.SOURCE_FIELDS) + ") VALUES "))
        self.assertNotIn("equity_style_monthly", sql)

    def test_no_formal_output_ddl_or_non_frozen_insert(self):
        source = rows(sut.INITIAL_MONTHS); sql = sut.insert_sql(source)
        for kind, statement, count in (("SOURCE_CREATE", "CREATE TABLE equity_style_monthly(month timestamp)", 0),
                                       ("SOURCE_CREATE", sut.CREATE_SQL, False), ("SOURCE_CREATE", sut.CREATE_SQL + ";", 0),
                                       ("SOURCE_INITIAL_INSERT", sql.replace("index_monthly", "equity_style_monthly", 1), 32),
                                       ("SOURCE_INITIAL_INSERT", sql + " UNION SELECT 1", 32), ("SOURCE_INITIAL_INSERT", sql, 33)):
            with self.subTest(kind=kind, count=count), self.assertRaises(RuntimeError):
                sut.validate_write(kind, statement, count, source)

    def test_wrong_month_missing_code_duplicate_key_rejected(self):
        for source in (rows(("202607", "202608")), rows(sut.INITIAL_MONTHS)[1:], rows(sut.INITIAL_MONTHS)[:-1] + [copy.deepcopy(rows(sut.INITIAL_MONTHS)[0])]):
            with self.subTest(source=source[:1]), self.assertRaises(RuntimeError):
                sut.insert_sql(source)

    def test_source_full_fields_bits_and_signed_zero_checked(self):
        source = rows(sut.INITIAL_MONTHS)
        proof = sut.strict_source_compare(copy.deepcopy(source), source)
        self.assertEqual((proof["full_field_comparisons"], proof["double_rawbit_comparisons"]), (448, 288))
        altered = copy.deepcopy(source); altered[0]["pct_chg"] = -0.0
        with self.assertRaises(RuntimeError):
            sut.strict_source_compare(altered, source)

    def test_source_null_text_order_and_ulp_differences_refused(self):
        source = rows(sut.INITIAL_MONTHS)
        for field, value in (("pct_chg", None), ("pct_chg", math.nextafter(0.0, math.inf)), ("layer", "other"), ("trade_date", "2026-06-25T00:00:00Z")):
            changed = copy.deepcopy(source); changed[0][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                sut.strict_source_compare(changed, source)
        with self.assertRaises(RuntimeError):
            sut.strict_source_compare(list(reversed(source)), source)

    def test_sql_quote_and_null_transport(self):
        self.assertEqual(sut.sql_literal("one'two", "SYMBOL"), "'one''two'")
        self.assertEqual(sut.sql_literal(None, "DOUBLE"), "NULL")
        self.assertEqual(sut.sql_literal(-0.0, "DOUBLE"), "-0.0")

    def test_frozen_jsonl_rawbits_tamper_rejected(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as directory:
            path = Path(directory) / "capture.jsonl"
            source = rows()
            capture = sut.audit.save_rows_new(path, source, sut.audit.SOURCE_TYPES)
            preflight = {"source_capture": capture, "source_census": sut.audit.source_census(source, list(sut.MONTHS))}
            self.assertEqual(sut.captured_source(preflight), source)
            values = path.read_text().splitlines(); value = json.loads(values[0]); value["raw_double_bits"]["pct_chg"] = "8000000000000000"
            values[0] = json.dumps(value); path.write_text("\n".join(values) + "\n")
            capture["sha256"] = sut.audit.digest(path)
            with self.assertRaises(RuntimeError):
                sut.captured_source(preflight)

    def test_increment_is_not_executable_and_manifest_is_scoped(self):
        contract = sut.INCREMENT_ADMISSION_CONTRACT
        self.assertIs(contract["executable_in_this_script_version"], False)
        self.assertEqual(contract["trade_months"], ["202608"])
        self.assertEqual(contract["source_only_rows"], 16)
        self.assertEqual(contract["DDL"], 0)
        self.assertIn("no D101 PID inheritance", contract["required_new_manifest"]["producer_proof"])


class DurableSubmissionTests(unittest.TestCase):
    def test_unknown_is_durable_before_http_and_never_resent(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as directory:
            base = Path(directory); output = base / "output.json"; claim = base / "claim.json"; result = journal(output)
            target = MagicMock(); target.verify.return_value = {"pid": 41560}
            def unknown(url, timeout):
                proof = sut.load(claim)
                self.assertEqual(proof["ack"], "UNKNOWN")
                self.assertIs(proof["attempted"], True)
                self.assertEqual(sut.load(output)["operations"][0]["ack"], "UNKNOWN")
                raise TimeoutError("mock unknown HTTP ACK")
            with patch.object(sut, "urlopen", side_effect=unknown) as driver:
                with self.assertRaises(TimeoutError):
                    sut.submit_once(target, "SOURCE_CREATE", sut.CREATE_SQL, 0, claim, output, result, MagicMock())
                with self.assertRaises(RuntimeError):
                    sut.submit_once(target, "SOURCE_CREATE", sut.CREATE_SQL, 0, claim, output, result, MagicMock())
                self.assertEqual(driver.call_count, 1)
            self.assertEqual(sut.load(claim)["ack"], "UNKNOWN")

    def test_successful_ack_and_existing_ack_claim_also_blocks_resend(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as directory:
            base = Path(directory); output = base / "output.json"; claim = base / "claim.json"; result = journal(output)
            target = MagicMock(); target.verify.return_value = {"pid": 41560}
            response = MagicMock(); response.__enter__.return_value = response; response.status = 200; response.read.return_value = b'{"ddl":"OK"}'
            with patch.object(sut, "urlopen", return_value=response) as driver:
                sut.submit_once(target, "SOURCE_CREATE", sut.CREATE_SQL, 0, claim, output, result, MagicMock())
                with self.assertRaises(RuntimeError):
                    sut.submit_once(target, "SOURCE_CREATE", sut.CREATE_SQL, 0, claim, output, result, MagicMock())
                self.assertEqual(driver.call_count, 1)
            self.assertEqual(sut.load(claim)["ack"], "ACKNOWLEDGED")
            self.assertEqual(sut.load(output)["operations"][0]["claim_sha256"], sut.audit.digest(claim))

    def test_cancel_before_intent_no_http_or_claim(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as directory:
            base = Path(directory); output = base / "output.json"; claim = base / "claim.json"; result = journal(output)
            with patch.object(sut.audit, "check_cancel", side_effect=RuntimeError("cancelled")), patch.object(sut, "urlopen") as driver:
                with self.assertRaises(RuntimeError):
                    sut.submit_once(MagicMock(), "SOURCE_CREATE", sut.CREATE_SQL, 0, claim, output, result, MagicMock())
                driver.assert_not_called(); self.assertFalse(claim.exists())

    def test_boundary_change_after_intent_stops_before_http(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as directory:
            base = Path(directory); output = base / "output.json"; claim = base / "claim.json"; result = journal(output)
            target = MagicMock(); target.verify.return_value = {"pid": 41560}
            with patch.object(sut, "urlopen") as driver:
                with self.assertRaises(RuntimeError):
                    sut.submit_once(target, "SOURCE_CREATE", sut.CREATE_SQL, 0, claim, output, result, MagicMock(side_effect=[None, RuntimeError("frontier changed")]))
                driver.assert_not_called()
            self.assertEqual(sut.load(claim)["ack"], "UNKNOWN")
            self.assertIs(sut.load(claim)["attempted"], False)

    def test_unconfirmed_http_success_stays_unknown(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as directory:
            base = Path(directory); output = base / "output.json"; claim = base / "claim.json"; result = journal(output)
            target = MagicMock(); target.verify.return_value = {"pid": 41560}
            response = MagicMock(); response.__enter__.return_value = response; response.status = 200; response.read.return_value = b'{"error":"rejected"}'
            with patch.object(sut, "urlopen", return_value=response), self.assertRaises(RuntimeError):
                sut.submit_once(target, "SOURCE_CREATE", sut.CREATE_SQL, 0, claim, output, result, MagicMock())
            self.assertEqual(sut.load(claim)["ack"], "UNKNOWN")

    def test_wrong_invocation_cannot_update_existing_journal(self):
        with tempfile.TemporaryDirectory(dir=sut.DIRECTORY) as directory:
            path = Path(directory) / "owned.json"; result = journal(path); result["invocation_id"] = "other"
            with self.assertRaises(RuntimeError):
                sut.save_progress(path, result)


class FiniteReadbackTests(unittest.TestCase):
    def test_visible_count_requires_settled_wal(self):
        reader = MagicMock()
        with patch.object(sut, "private_state", side_effect=[{"exists": True, "settled": False, "actual_select_count": 32}, {"exists": True, "settled": True, "actual_select_count": 32}]), patch.object(sut.time, "sleep"):
            value = sut.wait_visible(reader, 32)
        self.assertIs(value["settled"], True)
        self.assertIsNone(reader.visibility_deadline)

    def test_visibility_deadline_never_accepts_late_success(self):
        reader = MagicMock()
        with patch.object(sut.time, "monotonic", side_effect=[0.0, 1.0, 31.0]), \
             patch.object(sut, "private_state", return_value={"exists": True, "settled": True, "actual_select_count": 32}), self.assertRaises(RuntimeError):
            sut.wait_visible(reader, 32)
        self.assertIsNone(reader.visibility_deadline)

    def test_absolute_read_timeout_closes_connection(self):
        connection = MagicMock()
        with patch.object(sut.time, "monotonic", side_effect=[1.0, 21.0]), self.assertRaises(RuntimeError):
            with sut.private_pg_deadline(connection, 20.0):
                pass
        connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
