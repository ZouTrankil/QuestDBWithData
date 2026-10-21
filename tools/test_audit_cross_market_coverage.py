"""组合来源只有沿成功原运行核对完整原文和二十列，才能作为日期参照。"""
import copy
import hashlib
import json
import pathlib
import sqlite3
import tempfile
import unittest
import zipfile
from contextlib import closing
from unittest.mock import patch
import datetime as dt

from tools.audit_cross_market_coverage import equity_daily_dates, verified_daily_sources


class CoreDayProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.folder = self.root / "var/market-data"
        (self.folder / "prepared").mkdir(parents=True)
        self.run = "market-core-test"
        self.day = "2026-10-19"
        self.observed = "2026-10-20T17:45:46.228583Z"
        point = {"date": self.day + "T00:00:00Z", "divCash": 0.0, "splitFactor": 1.0}
        self.row = {"trade_date": point["date"], "symbol": "SPY", "provider": "TIINGO", "venue": "US_COMPOSITE",
                    "currency": "USD", "raw_price_basis": "VENDOR_UNADJUSTED", "observed_at": self.observed,
                    "dividend": 0.0, "split_factor": 1.0}
        for field, value in [("open", 7.0), ("high", 9.0), ("low", 6.0), ("close", 8.0), ("volume", 123.0)]:
            point[field] = point["adj" + field.title()] = value
            self.row[field] = self.row["adj_" + field] = value
        self.daily_body = json.dumps([point]).encode()
        self.minute_body = b'[{"date":"2026-10-19T13:30:00Z","close":8}]'
        self.daily_sha = self.sha(self.daily_body)
        self.minute_sha = self.sha(self.minute_body)
        self.row["source_sha256"] = self.daily_sha
        self.archive = self.folder / "sources.zip"
        members = [{"symbol": "SPY", "kind": kind, "entry": kind + "/SPY.response", "sha256": digest, "observedAt": self.observed}
                   for kind, digest in [("DAILY", self.daily_sha), ("MINUTE", self.minute_sha)]]
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr("manifest.json", json.dumps({"version": 1, "kind": "TIINGO_CORE_DAY", "from": self.day, "through": self.day, "members": members}))
            archive.writestr("DAILY/SPY.response", self.daily_body)
            archive.writestr("MINUTE/SPY.response", self.minute_body)
        self.package_sha = self.sha(self.archive.read_bytes())
        self.prepared = {"definition": {"datasetId": "us_equity_daily"}, "sourceSha256": self.package_sha,
                         "raw": str(self.archive), "rows": [self.row],
                         "evidence": {"packageSha256": self.package_sha, "day": self.day,
                                      "child": {"id": "DAILY", "dataset": "us_equity_daily", "rows": 1, "symbols": ["SPY"]}}}
        self.request = {"definition": {"datasetId": "us_equity_daily"}, "from": self.day, "to": self.day,
                        "parameters": {"source_sha256": self.package_sha}}
        with closing(sqlite3.connect(self.folder / "sync-ledger.sqlite3")) as db:
            db.execute("create table sync_runs(id text primary key,frozen_json text)")
            db.execute("create table sync_entries(id text primary key,kind text,state text)")
            db.commit()
        self.install()

    @staticmethod
    def sha(data):
        return hashlib.sha256(data).hexdigest()

    def install(self, state="VERIFIED"):
        body = json.dumps(self.prepared).encode()
        (self.folder / "prepared" / (self.run + ".json")).write_bytes(body)
        self.request["parameters"]["prepared_sha256"] = self.sha(body)
        with closing(sqlite3.connect(self.folder / "sync-ledger.sqlite3")) as db:
            db.execute("insert or replace into sync_runs values(?,?)", (self.run, json.dumps(self.request)))
            db.execute("insert or replace into sync_entries values(?,?,?)", (self.run, "RUN", state))
            db.commit()

    def test_only_verified_daily_leaf_is_traced(self):
        result = verified_daily_sources(self.root)
        self.assertEqual({self.package_sha, self.daily_sha}, result)
        self.assertNotIn(self.minute_sha, result)

    def test_uncertain_native_run_does_not_certify_any_source(self):
        self.install("IN_DOUBT")
        self.assertEqual(set(), verified_daily_sources(self.root))

    def test_arbitrary_archive_without_core_child_contract_does_not_certify_leaves(self):
        self.prepared["evidence"] = {}
        self.install()
        self.assertEqual({self.package_sha}, verified_daily_sources(self.root))

    def test_non_daily_native_run_does_not_certify_daily_members(self):
        self.request["definition"]["datasetId"] = "us_equity_intraday"
        self.install()
        self.assertEqual(set(), verified_daily_sources(self.root))

    def test_archive_bytes_changed_after_native_freeze_are_rejected(self):
        with self.archive.open("ab") as out:
            out.write(b"changed")
        with self.assertRaisesRegex(ValueError, "ZIP 摘要"):
            verified_daily_sources(self.root)

    def test_prepared_bytes_changed_after_native_freeze_are_rejected(self):
        path = self.folder / "prepared" / (self.run + ".json")
        path.write_bytes(path.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "prepared"):
            verified_daily_sources(self.root)

    def test_leaf_hash_cannot_point_at_a_minute_member(self):
        self.prepared["rows"][0]["source_sha256"] = self.minute_sha
        self.install()
        with self.assertRaisesRegex(ValueError, "二十列"):
            verified_daily_sources(self.root)

    def test_unlinked_price_value_is_not_accepted_despite_valid_root_hashes(self):
        self.prepared["rows"] = [copy.deepcopy(self.row)]
        self.prepared["rows"][0]["close"] = 88.0
        self.install()
        with self.assertRaisesRegex(ValueError, "二十列"):
            verified_daily_sources(self.root)


class DateSetAuditTest(unittest.TestCase):
    class Prices:
        def __init__(self, actual, changes=False):
            self.reference = ["2026-10-19T00:00:00Z", "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z"]
            self.actual = actual
            self.detail_reads = 0
            self.inventory_reads = 0
            self.changes = changes

        def inventory(self):
            result = []
            for symbol, points in [("SPY", self.reference), ("ABC", self.actual)]:
                result.append({"symbol": symbol, "provider": "TIINGO", "rows": len(points), "distinct_dates": len(set(points)),
                               "first_date": min(points), "last_date": max(points)})
            return result

        def query(self, sql):
            if "CROSS JOIN" in sql:
                raise AssertionError("不得恢复全证券全日期笛卡尔积")
            if "from tables()" in sql:
                return [{"id": 1, "directoryName": "original"}]
            if "group by symbol,provider,source_sha256" in sql:
                return [{k: v for k, v in dict(row, source_sha256="trusted").items() if k != "distinct_dates"} for row in self.inventory()]
            if "count_distinct(trade_date)" in sql:
                self.inventory_reads += 1
                rows = self.inventory()
                if self.changes and self.inventory_reads > 1:
                    rows[1]["rows"] += 1
                return rows
            if "select trade_date ref_date,source_sha256" in sql:
                return [{"ref_date": point, "source_sha256": "trusted"} for point in self.reference]
            if sql.startswith("SELECT symbol,provider,trade_date ref_date"):
                assert "NOT IN" in sql
                return [{"symbol": "ABC", "provider": "TIINGO", "ref_date": point} for point in self.actual if point not in self.reference]
            if sql.startswith("SELECT trade_date FROM us_equity_daily WHERE symbol='ABC'"):
                self.detail_reads += 1
                return [{"trade_date": point} for point in self.actual]
            raise AssertionError(sql)

    def audit(self, points, changes=False):
        prices = self.Prices(points, changes)
        with patch("tools.audit_cross_market_coverage.verified_daily_sources", return_value={"trusted"}):
            result = equity_daily_dates(prices, pathlib.Path("."), dt.date(2026, 10, 19), dt.date(2026, 10, 22))
        return prices, next(row for row in result["series"] if row["symbol"] == "ABC")

    def test_membership_and_unique_count_prove_complete_dates_without_cross_product(self):
        prices, row = self.audit(self.Prices([]).reference)
        self.assertTrue(row["collectedDateSetMatchesReference"])
        self.assertEqual(0, prices.detail_reads)

    def test_equal_counts_cannot_hide_a_missing_date_offset_by_extra_date(self):
        prices, row = self.audit(["2026-10-19T00:00:00Z", "2026-10-20T00:00:00Z", "2026-10-22T00:00:00Z"])
        self.assertFalse(row["collectedDateSetMatchesReference"])
        self.assertEqual(["2026-10-21"], row["missingReferenceDates"])
        self.assertEqual(["2026-10-22"], row["datesOutsideReference"])
        self.assertEqual(1, prices.detail_reads)

    def test_internal_missing_date_is_enumerated(self):
        _, row = self.audit(["2026-10-19T00:00:00Z", "2026-10-21T00:00:00Z"])
        self.assertEqual(["2026-10-20"], row["missingReferenceDates"])
        self.assertFalse(row["collectedDateSetMatchesReference"])

    def test_duplicates_cannot_substitute_for_a_missing_day(self):
        _, row = self.audit(["2026-10-19T00:00:00Z", "2026-10-19T00:00:00Z", "2026-10-21T00:00:00Z"])
        self.assertEqual(["2026-10-20"], row["missingReferenceDates"])
        self.assertFalse(row["collectedDateSetMatchesReference"])

    def test_non_midnight_timestamp_does_not_count_as_that_business_date(self):
        _, row = self.audit(["2026-10-19T00:00:00Z", "2026-10-20T01:00:00Z", "2026-10-21T00:00:00Z"])
        self.assertEqual(["2026-10-20"], row["missingReferenceDates"])
        self.assertEqual(["2026-10-20"], row["datesOutsideReference"])
        self.assertFalse(row["collectedDateSetMatchesReference"])

    def test_source_inventory_change_during_detailed_reads_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "发生变化"):
            self.audit(["2026-10-19T00:00:00Z", "2026-10-21T00:00:00Z"], changes=True)


if __name__ == "__main__":
    unittest.main()
