"""验证研究数据的容量保护、时间口径与拒绝路径；不访问网络或正式库。"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import pandas as pd

from tools.research_playbook.data import write_bounded, parse_fred, parse_stablecoins, raw_source, sha
from tools.research_playbook.analysis import liquidity_proxy, quarterly_cashflow, btc_baselines, block_bootstrap_mean


class PlaybookTests(unittest.TestCase):
    def test_capacity_counts_replacement_peak_and_preserves_old(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write_bounded(root, "old", b"12345", 10)
            with self.assertRaises(ValueError):
                write_bounded(root, "old", b"123456", 10)
            self.assertEqual((root / "old").read_bytes(), b"12345")
            self.assertEqual(list(root.iterdir()), [root / "old"])
            with self.assertRaises(ValueError):
                write_bounded(root, "../escape", b"x", 10)

    def test_corrupt_raw_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / "raw").mkdir()
            raw = root / "raw/example.source"; raw.write_bytes(b"original")
            raw.with_suffix(".properties").write_text(f"sha256={sha(raw)}\nurl=https://example.org\nbytes=8\n")
            with patch("tools.research_playbook.data.settings", return_value={"sources": "example", "example.url": "https://example.org"}), patch("tools.research_playbook.data.data_root", return_value=root):
                self.assertEqual(raw_source("example")[0], raw)
                raw.write_bytes(b"modified")
                with self.assertRaises(ValueError):
                    raw_source("example")

    def test_fred_retains_missing_and_rejects_duplicate_dates(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / "data.csv"
            p.write_text("observation_date,S\n2024-01-01,2\n2024-01-02,.\n")
            self.assertTrue(pd.isna(parse_fred(p, "S").iloc[1].value))
            p.write_text("observation_date,S\n2024-01-01,2\n2024-01-01,3\n")
            with self.assertRaises(ValueError): parse_fred(p, "S")

    def test_stablecoin_missing_is_not_zero(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / "data.json"
            p.write_text(json.dumps([{"date": "1704067200", "totalCirculatingUSD": {"peggedUSD": None}}]))
            with self.assertRaises(ValueError): parse_stablecoins(p)

    def test_liquidity_uses_same_wednesday_and_billion_conversion(self):
        rows = [("2024-01-03", "WALCL", 10000), ("2024-01-03", "WDTGAL", 1000),
                ("2024-01-03", "RRPONTSYD", 2), ("2024-01-10", "WALCL", 20000),
                ("2024-01-10", "WDTGAL", 1000), ("2024-01-11", "RRPONTSYD", 4)]
        f = pd.DataFrame(rows, columns=["date", "series", "value"])
        f.date = pd.to_datetime(f.date, utc=True)
        result = liquidity_proxy(f)
        self.assertEqual(len(result), 1)
        self.assertEqual(result.iloc[0].proxy_usd_m, 7000)

    def test_cashflow_differences_keep_revision_visibility(self):
        f = pd.DataFrame({"symbol": ["X"] * 3, "fiscal_start": ["2023-07-01"] * 3,
                          "quarter": [1, 2, 3], "value": [10., 25., 30.],
                          "available_at": ["2024-08-01", "2024-02-01", "2024-05-01"],
                          "vintage": ["restated"] * 3, "unit": ["USD"] * 3, "metric": ["CFO"] * 3})
        out = quarterly_cashflow(f)
        self.assertEqual(out.quarter_value.tolist(), [10., 15., 5.])
        self.assertEqual(out.iloc[1].quarter_available_at, pd.Timestamp("2024-08-01", tz="UTC"))
        for invalid in [f.iloc[[0, 2]], pd.concat([f, f.iloc[[0]]])]:
            with self.assertRaises(ValueError): quarterly_cashflow(invalid)

    def test_btc_microsecond_dates_valid_but_missing_day_rejected(self):
        dates = pd.date_range("2020-01-01", periods=260, tz="UTC").as_unit("us")
        f = pd.DataFrame({"open_time": dates, "open": 100., "close": 100., "high": 101., "low": 99.})
        _, scores = btc_baselines(f)
        self.assertLess(scores.loc["hold", "totalReturn"], 0)
        with self.assertRaises(ValueError): btc_baselines(f.drop(index=100))
        f.loc[100, "close"] = np.inf
        with self.assertRaises(ValueError): btc_baselines(f)

    def test_block_bootstrap_is_reproducible_and_bounded(self):
        values = np.arange(60, dtype=float)
        np.testing.assert_array_equal(block_bootstrap_mean(values), block_bootstrap_mean(values))
        with self.assertRaises(ValueError): block_bootstrap_mean(values, repetitions=10001)


if __name__ == "__main__":
    unittest.main()
