"""历史身份诊断不能用当前类别替代旧证券窗口。"""
import datetime as dt
import unittest
from tools.audit_us_security_identity import review


class IdentityReviewTest(unittest.TestCase):
    start = dt.date(2006, 1, 1)
    through = dt.date(2026, 10, 16)

    @staticmethod
    def row(number, symbol, exchange, kind, first, last, candidate=True):
        return {'source_row': number, 'symbol': symbol, 'exchange': exchange, 'asset_type': kind, 'currency': 'USD', 'source_start_date': first, 'source_end_date': last, 'daily_candidate': candidate}

    def test_current_etf_retains_older_stock_without_claiming_its_coverage(self):
        history = [self.row(1, 'AAAP', 'NASDAQ', 'Stock', '2015-11-11', '2018-02-20'), self.row(2, 'AAAP', 'NASDAQ', 'ETF', '2026-06-23', '2026-10-16', False)]
        task = {'id': 'original', 'phase': 'PLANNED'}
        report = review(history, [{'symbol': 'AAAP', 'is_etf': True}], {'AAAP': task}, self.start, self.through)
        group = report['groups'][0]
        self.assertEqual(2, len(group['historicalRows']))
        self.assertEqual(1, report['candidateStockRows'])
        self.assertIn('CURRENT_ETF_REQUIRES_HISTORY_REVIEW', group['flags'])
        self.assertIn('VENDOR_ASSET_TYPE_CONFLICT', group['flags'])
        self.assertEqual([], group['overlapsInRequestedRange'])
        self.assertEqual('PLANNED', task['phase'])
        self.assertEqual('IDENTITY_UNPROVEN', group['coverageConclusion'])

    def test_overlapping_exchange_windows_stay_separate(self):
        history = [self.row(7, 'AMTD', 'NYSE', 'Stock', '2019-08-05', '2026-10-16'), self.row(8, 'AMTD', 'NASDAQ', 'Stock', '1997-03-04', '2020-10-07')]
        report = review(history, [{'symbol': 'AMTD', 'is_etf': False}], {'AMTD': {'phase': 'VERIFIED'}}, self.start, self.through)
        group = report['groups'][0]
        self.assertEqual(2, report['candidateStockRows'])
        self.assertEqual(1, report['candidateStockSymbols'])
        self.assertEqual([{'leftSourceRow': 7, 'rightSourceRow': 8, 'from': '2019-08-05', 'through': '2020-10-07'}], group['overlapsInRequestedRange'])
        self.assertIn('OVERLAPPING_VENDOR_WINDOWS', group['flags'])
        self.assertFalse(report['wholeGoalComplete'])

    def test_old_outside_window_is_retained_and_no_flags_does_not_prove_completion(self):
        history = [self.row(1, 'X', 'NYSE', 'Stock', '1990-01-01', '2005-12-31', False), self.row(2, 'X', 'NYSE', 'Stock', '2020-01-02', '2026-10-16')]
        report = review(history, [], {}, self.start, self.through)
        self.assertEqual(2, len(report['groups'][0]['historicalRows']))
        self.assertEqual([], report['groups'][0]['overlapsInRequestedRange'])
        ordinary = review([history[1]], [], {}, self.start, self.through)
        self.assertEqual(0, ordinary['reviewSymbols'])
        self.assertFalse(ordinary['wholeGoalComplete'])


if __name__ == '__main__':
    unittest.main()
