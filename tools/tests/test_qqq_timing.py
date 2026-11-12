"""QQQ扩展的执行时序、隔夜风险和现金计息边界。"""
import unittest
import numpy as np
import pandas as pd
from tools.plan_1029_research import simulate
from tools.research_playbook.qqq_timing import schedule


class TimingTests(unittest.TestCase):
    def setUp(self):
        self.index=pd.bdate_range('2020-01-01',periods=4)
        self.opens=pd.DataFrame({'QQQ':[100.,100.,50.,50.]},index=self.index)
        self.closes=self.opens.copy()
        self.hours={str(d.date()):{'open':str(d+pd.Timedelta(hours=14,minutes=30)), 'close':str(d+pd.Timedelta(hours=21))} for d in self.index}

    def test_exit_still_bears_overnight_gap(self):
        targets=pd.DataFrame({'QQQ':[1.,np.nan,0.,np.nan]},index=self.index)
        d,_,tr=simulate(self.opens,self.closes,'custom',self.index[0],self.index[-1],0,'etf',hours=self.hours,targets=targets)
        self.assertAlmostEqual(d.nav.iloc[-1],50000.)
        self.assertEqual(len(tr),2)

    def test_sold_cash_cannot_earn_prior_overnight_interest(self):
        targets=pd.DataFrame({'QQQ':[1.,np.nan,0.,np.nan]},index=self.index)
        cash=pd.Series([0.,0.,.1,0.],index=self.index)
        d,_,_=simulate(self.opens,self.closes,'custom',self.index[0],self.index[-1],0,'etf',hours=self.hours,targets=targets,cash_returns=cash)
        self.assertAlmostEqual(d.nav.iloc[-1],50000.)
        targets.iloc[0]=0.
        d,_,_=simulate(self.opens,self.closes,'custom',self.index[0],self.index[-1],0,'etf',hours=self.hours,targets=targets,cash_returns=cash)
        self.assertAlmostEqual(d.nav.iloc[-1],110000.)

    def test_signal_executes_next_day(self):
        idx=pd.bdate_range('2020-01-01',periods=260)
        close=pd.DataFrame({'QQQ':[100.]*258+[200.,200.]},index=idx)
        target=schedule(close,'macd_12_26_9',idx[252:])
        self.assertTrue(pd.isna(target.iloc[-2,0]))
        self.assertEqual(target.iloc[-1,0],1.)

    def test_monthly_fixed_weight_is_not_daily_rebalance(self):
        idx=pd.bdate_range('2020-01-01',periods=260)
        close=pd.DataFrame({'QQQ':100.},index=idx)
        target=schedule(close,'fixed50',idx)
        self.assertEqual(target.QQQ.notna().sum(),len(set(idx.to_period('M'))))

    def test_cash_and_schedule_reject_misalignment(self):
        target=pd.DataFrame({'QQQ':[0.]},index=self.index[:1])
        with self.assertRaisesRegex(ValueError,'目标仓位'):
            simulate(self.opens,self.closes,'custom',self.index[0],self.index[-1],0,'etf',hours=self.hours,targets=target)

if __name__=='__main__':unittest.main()
