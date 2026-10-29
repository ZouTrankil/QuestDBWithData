"""验证1029基线的时点、现金份额及自融资成本，不依赖实库。"""

import unittest
import numpy as np
import pandas as pd
from tools.plan_1029_research import rebalance, target_weights, simulate
from tools.plan_1029_audit import gaps, candle_issues, corporate_actions
from datetime import datetime, timezone, timedelta


class ResearchTests(unittest.TestCase):
    def test_cash_dividend_uses_current_close_reinvestment(self):
        rows = [
            dict(
                symbol="SPY",
                trade_date=datetime(2026, 1, 2, tzinfo=timezone.utc),
                close=100.0,
                adj_close=100.0 / 1.01,
                volume=100.0,
                adj_volume=100.0,
                dividend=0.0,
                split_factor=1.0,
            ),
            dict(
                symbol="SPY",
                trade_date=datetime(2026, 1, 5, tzinfo=timezone.utc),
                close=99.0,
                adj_close=99.0,
                volume=100.0,
                adj_volume=100.0,
                dividend=0.99,
                split_factor=1.0,
            ),
        ]
        self.assertTrue(corporate_actions(rows)["symbols"]["SPY"]["passed"])
        rows[1]["adj_close"] = 98.0
        self.assertFalse(corporate_actions(rows)["symbols"]["SPY"]["passed"])

    def test_reverse_split_adjusts_price_and_integer_volume(self):
        rows = [
            dict(
                symbol="USO",
                trade_date=datetime(2020, 4, 28, tzinfo=timezone.utc),
                close=2.13,
                adj_close=17.04,
                volume=155154495.0,
                adj_volume=19394311.0,
                dividend=0.0,
                split_factor=1.0,
            ),
            dict(
                symbol="USO",
                trade_date=datetime(2020, 4, 29, tzinfo=timezone.utc),
                close=18.0,
                adj_close=18.0,
                volume=23250046.0,
                adj_volume=23250046.0,
                dividend=0.0,
                split_factor=0.125,
            ),
        ]
        self.assertTrue(corporate_actions(rows)["symbols"]["USO"]["passed"])
        rows[0]["adj_volume"] = 19394310.0
        self.assertFalse(corporate_actions(rows)["symbols"]["USO"]["passed"])

    def test_initial_buy_and_liquidation_pay_both_sides(self):
        cash, units, delta, cost = rebalance(
            100, np.array([0.0]), np.array([10.0]), np.array([1.0]), 0.01
        )
        self.assertAlmostEqual(cash, 0)
        self.assertAlmostEqual(units[0] * 10, 100 / 1.01)
        cash, units, delta, cost = rebalance(
            cash, units, np.array([10.0]), np.array([0.0]), 0.01
        )
        self.assertAlmostEqual(cash, 100 / 1.01 * 0.99)
        self.assertEqual(units[0], 0)

    def test_trend_filter_keeps_rejected_half_as_cash(self):
        data = pd.DataFrame(
            {
                "A": np.r_[np.full(199, 100.0), 99.0],
                "B": np.linspace(80, 100, 200),
                "C": np.linspace(100, 80, 200),
            }
        )
        # A的126日收益仍高于C，但位于自身均线下；不将其份额补选给C。
        weights = target_weights(data, "momentum_trend")
        self.assertEqual(weights["A"], 0)
        self.assertEqual(weights["B"], 0.5)
        self.assertEqual(weights["C"], 0)

    def test_crypto_flat_trend_is_cash(self):
        self.assertEqual(
            target_weights(pd.DataFrame({"BTC": [100.0] * 200}), "trend").iloc[0], 0
        )

    def test_insufficient_warmup_cannot_trade(self):
        self.assertEqual(
            target_weights(
                pd.DataFrame({"A": [1.0] * 126, "B": [2.0] * 126}), "momentum"
            ).sum(),
            0,
        )

    def test_future_prices_do_not_change_prior_trades(self):
        dates = pd.date_range("2020-01-01", periods=260, tz="UTC")
        base = pd.DataFrame({"BTC": np.linspace(100, 200, 260)}, index=dates)
        a = simulate(base, base, "trend", "2020-08-01", "2020-09-10", 0.001, "crypto")[
            2
        ]
        future = base.copy()
        future.loc[future.index > "2020-08-15"] *= 10
        b = simulate(
            future, future, "trend", "2020-08-01", "2020-09-10", 0.001, "crypto"
        )[2]
        pd.testing.assert_frame_equal(
            a.loc[a.day < "2020-08-15"].reset_index(drop=True),
            b.loc[b.day < "2020-08-15"].reset_index(drop=True),
        )

    def test_crypto_rotation_only_uses_monday_execution(self):
        dates = pd.date_range("2020-01-01", periods=280, tz="UTC")
        prices = pd.DataFrame(
            {"BTC": np.linspace(100, 200, 280), "ETH": np.linspace(100, 300, 280)},
            index=dates,
        )
        trades = simulate(
            prices, prices, "rotation", "2020-08-03", "2020-10-01", 0, "crypto"
        )[2]
        self.assertTrue(
            all(
                pd.Timestamp(x).weekday() == 0
                for x in trades.loc[trades.event == "rebalance", "day"]
            )
        )

    def test_grid_detects_duplicate_cancelling_missing_count(self):
        first = datetime(2021, 1, 1, tzinfo=timezone.utc)
        value = gaps([first, first], first, first + timedelta(hours=2), 3600)
        self.assertEqual(value["duplicates"], 1)
        self.assertEqual(len(value["missing"]), 1)

    def test_invalid_ohlc_rejected(self):
        row = dict(
            ts=datetime(2021, 1, 1, tzinfo=timezone.utc),
            open=10.0,
            high=9.0,
            low=8.0,
            close=8.5,
            volume=1.0,
        )
        self.assertEqual(candle_issues([row], "ts")[0]["kind"], "ohlc_boundary")


if __name__ == "__main__":
    unittest.main()
