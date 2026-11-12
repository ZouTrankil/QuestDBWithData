"""QQQ长期择时研究：只读冻结原件，复用原回放器，不接管正式数据生产。"""
from __future__ import annotations

import json
from pathlib import Path
import numpy as np
import pandas as pd
import exchange_calendars as xc
from tools.plan_1029_research import simulate, metrics
from .data import ROOT, raw_source, sha

OUT = ROOT / 'var/research-playbook/qqq-long'


def load_prices():
    path, receipt = raw_source('qqq_long_2025')
    body = json.loads(path.read_text())
    if body['chart']['error']:
        raise ValueError(body['chart']['error'])
    data = body['chart']['result'][0]
    if data['meta']['symbol'] != 'QQQ':
        raise ValueError('证券不是QQQ')
    dates = pd.to_datetime(data['timestamp'], unit='s', utc=True).tz_convert('America/New_York').tz_localize(None).normalize()
    frame = pd.DataFrame(data['indicators']['quote'][0], index=dates)
    frame['adjclose'] = data['indicators']['adjclose'][0]['adjclose']
    sessions = xc.get_calendar('XNYS', start='1999-03-10', end='2025-12-31').sessions
    if not frame.index.equals(sessions):
        raise ValueError('日历缺口、重复或越界')
    if not np.isfinite(frame.to_numpy()).all() or (frame[['open','high','low','close','adjclose']] <= 0).any().any():
        raise ValueError('价格为空或非法')
    if ((frame[['open','close']].min(axis=1) < frame.low) | (frame[['open','close']].max(axis=1) > frame.high)).any():
        raise ValueError('OHLC越界')
    # Yahoo OHLC已拆股调整；不能再次应用拆股。用同日调整因子近似总回报开盘价。
    factor = frame.adjclose / frame.close
    op = (frame.open * factor).to_frame('QQQ')
    cl = frame.adjclose.to_frame('QQQ')
    proof = dict(source=receipt, first=str(dates[0].date()), last=str(dates[-1].date()), rows=len(frame),
                 calendar='exchange_calendars 4.13.2 XNYS', calendar_missing=0, ohlc_issues=0,
                 dividend_events=len(data.get('events',{}).get('dividends',{})),
                 split_events=len(data.get('events',{}).get('splits',{})),
                 historical_pit=False, adjustment='当前供应商复权近似；未核验全部发行人分红支付日')
    return op, cl, proof


def cash_series(index):
    path, receipt = raw_source('irx_long_2025')
    data = json.loads(path.read_text())['chart']['result'][0]
    if data['meta']['symbol'] != '^IRX':
        raise ValueError('现金来源不是IRX')
    dates = pd.to_datetime(data['timestamp'], unit='s', utc=True).tz_convert('America/New_York').tz_localize(None).normalize()
    rates = pd.Series(data['indicators']['quote'][0]['close'], index=dates).dropna()
    # 用两日以前观测的贴现率作ACT/360单利代理；留出公布延迟，不冒充国债总回报。
    prior = index - pd.Timedelta(days=2)
    lag = rates.reindex(prior, method='ffill').to_numpy()
    if not np.isfinite(lag).all():
        raise ValueError('现金利率覆盖不足')
    gaps = index.to_series().diff().dt.days.fillna(0).to_numpy()
    return pd.Series(lag / 100 * gaps / 360, index=index), receipt


def schedule(closes, rule, index):
    """只用前一交易日信号；固定仓位按月调仓，趋势只在目标改变时成交。"""
    s = closes.QQQ
    if rule.startswith('macd'):
        fast, slow, signal = map(int, rule.split('_')[1:])
        dif = s.ewm(span=fast, adjust=False).mean() - s.ewm(span=slow, adjust=False).mean()
        state = (dif > dif.ewm(span=signal, adjust=False).mean()).astype(float).shift(1).loc[index]
    elif rule.startswith('sma'):
        state = (s > s.rolling(int(rule[3:])).mean()).astype(float).shift(1).loc[index]
    else:
        weight = {'hold':1., 'cash':0., 'fixed25':.25, 'fixed50':.5, 'fixed75':.75}[rule]
        state = pd.Series(weight, index=index)
    if rule.startswith('fixed'):
        active = index.to_period('M').to_series(index=index).ne(index.to_period('M').to_series(index=index).shift())
    else:
        active = state.ne(state.shift())
    active.iloc[0] = True
    return state.where(active).to_frame('QQQ')


def summarize(daily, trades, cash):
    result = metrics(daily, trades, 100000, 252)
    # 主报告按实际日历时长年化，原引擎252日口径仍保留以便追溯。
    years = (pd.Timestamp(daily.day.iloc[-1])-pd.Timestamp(daily.day.iloc[0])).days / 365.2425
    result['cagr'] = (daily.nav.iloc[-1] / 100000) ** (1/years) - 1
    excess = daily.return_net.to_numpy() - cash.to_numpy()
    result['excessSharpe'] = excess.mean()/excess.std(ddof=1)*np.sqrt(252) if excess.std(ddof=1)>0 else 0.
    return result


def block_intervals(diff, lengths=(21,63,126), draws=5000):
    """对已实现的配对收益差抽连续块；不将重叠长窗当独立样本。"""
    out=[]
    for length in lengths:
        rng=np.random.default_rng(20261110)
        n=len(diff); means=[]
        for _ in range(draws//100):
            starts=rng.integers(0,n-length+1,size=(100,int(np.ceil(n/length))))
            idx=(starts[:,:,None]+np.arange(length)).reshape(100,-1)[:,:n]
            means.extend(diff[idx].mean(axis=1)*252)
        lo,hi=np.quantile(means,[.025,.975])
        out.append(dict(block=length,mean=float(diff.mean()*252),low=float(lo),high=float(hi)))
    return out


def run():
    OUT.mkdir(parents=True,exist_ok=True)
    opens,closes,proof=load_prices()
    index=opens.index[252:]
    calendar=xc.get_calendar('XNYS',start='1999-03-10',end='2025-12-31')
    hours={str(day.date()):{'open':calendar.session_open(day).isoformat(),'close':calendar.session_close(day).isoformat()} for day in opens.index}
    cash, cashproof=cash_series(index)
    proof['cash_source']=cashproof
    rules=['hold','cash','fixed25','fixed50','fixed75','macd_12_26_9','sma200',
           'macd_8_17_9','macd_19_39_9','sma150','sma250']
    rows=[]; daily_map={}; trade_map={}; cost_rows=[]
    for rule in rules:
        targets=schedule(closes,rule,index)
        for bps in ([0,5,10,20] if rule in ['hold','cash','fixed25','fixed50','fixed75','macd_12_26_9','sma200'] else [10]):
            daily,weights,trades=simulate(opens,closes,rule,index[0],index[-1],bps/10000,'etf',hours=hours,targets=targets,cash_returns=cash)
            m=dict(rule=rule,cost_bps=bps,**summarize(daily,trades,cash))
            cost_rows.append(m)
            if bps==10:
                rows.append(m); daily_map[rule]=daily; trade_map[rule]=trades
                daily.to_csv(OUT/f'{rule}-daily.csv',index=False)
                trades.to_csv(OUT/f'{rule}-trades.csv',index=False)
            print(rule,bps,round(m['cagr']*100,3),round(m['maxDrawdown']*100,3),flush=True)
    pd.DataFrame(rows).to_csv(OUT/'summary.csv',index=False)
    pd.DataFrame(cost_rows).to_csv(OUT/'costs.csv',index=False)
    returns=pd.DataFrame({k:v.return_net.to_numpy() for k,v in daily_map.items()},index=index)
    returns.to_csv(OUT/'returns.csv',index_label='day')
    # 完整路径上分段与滚动统计；窗口开始归一化，不重置策略信号或虚构独立试验。
    segments=[]; rolling=[]
    periods=[('early',str(index[0].date()),'2006-12-31'),('gfc','2007-01-01','2012-12-31'),
             ('growth','2013-01-01','2019-12-31'),('recent','2020-01-01','2025-12-31')]
    for name,start,end in periods:
        sample=returns.loc[start:end]
        for rule in rules:
            wealth=np.r_[1.,(1+sample[rule]).cumprod().to_numpy()]
            segments.append(dict(period=name,rule=rule,cagr=wealth[-1]**(252/len(sample))-1,
                                 maxDrawdown=float((wealth/np.maximum.accumulate(wealth)-1).min())))
    for years in [5,10,15,20]:
        for start in index.to_series().groupby(index.to_period('M')).first():
            end=start+pd.DateOffset(years=years)
            if end>index[-1]:continue
            sample=returns.loc[(index>=start)&(index<end)]
            growth=(1+sample).prod()**(1/years)-1
            for rule in rules:
                rolling.append(dict(years=years,start=str(start.date()),rule=rule,cagr=growth[rule],delta=growth[rule]-growth['hold']))
    pd.DataFrame(segments).to_csv(OUT/'segments.csv',index=False)
    pd.DataFrame(rolling).to_csv(OUT/'rolling.csv',index=False)
    events=[]; attribution=[]; intervals={}
    past=closes.QQQ.pct_change(20).shift(1).loc[index]
    for rule in ['macd_12_26_9','sma200']:
        tr=trade_map[rule].query("event == 'rebalance'").copy()
        tr['past20']=pd.to_datetime(tr.day).map(past)
        for direction,group in tr.groupby(tr.notionalChange>0):
            events.append(dict(rule=rule,side='buy' if direction else 'sell',count=len(group),
                               past20_mean=group.past20.mean(),positive_fraction=(group.past20>0).mean()))
        # 按基准当日涨跌拆解相对净回报，所有日相加精确等于总对数财富差。
        diff=np.log1p(returns[rule])-np.log1p(returns.hold)
        for sign in [True,False]:
            mask=returns.hold>0 if sign else returns.hold<=0
            attribution.append(dict(rule=rule,hold_day='up' if sign else 'down_or_flat',days=int(mask.sum()),log_wealth_difference=float(diff[mask].sum())))
        intervals[rule]=block_intervals((returns[rule]-returns.hold).to_numpy())
    pd.DataFrame(events).to_csv(OUT/'events.csv',index=False)
    pd.DataFrame(attribution).to_csv(OUT/'attribution.csv',index=False)
    # 下一年度固定暴露来自此前五年；避免用全期最优权重构造同风险对照。
    matchrows=[]
    for rule in ['macd_12_26_9','sma200']:
        for mode in ['exposure','volatility']:
            matched=pd.DataFrame(np.nan,index=index,columns=['QQQ']); eligible=[]
            invested=pd.Series(daily_map[rule].investedFraction.to_numpy(),index=index)
            for year in sorted(set(index.year)):
                if year<int(index[0].year)+6:continue
                train=(index>=pd.Timestamp(year-5,1,1))&(index<pd.Timestamp(year,1,1))
                test=index[index.year==year]
                if mode=='exposure': w=float(invested.loc[train].mean())
                else:
                    excess_hold=returns.hold.loc[train].to_numpy()-cash.loc[train].to_numpy()
                    targetvol=returns[rule].loc[train].std(ddof=1)
                    grid=np.linspace(0,1,1001)
                    vols=np.std(cash.loc[train].to_numpy()[:,None]+excess_hold[:,None]*grid,axis=0,ddof=1)
                    w=float(grid[np.argmin(abs(vols-targetvol))])
                months=test.to_series().groupby(test.to_period('M')).first()
                matched.loc[months,'QQQ']=w;eligible.extend(test)
            eligible=pd.DatetimeIndex(eligible)
            d,_,t=simulate(opens,closes,rule,eligible[0],eligible[-1],.001,'etf',hours=hours,targets=matched.loc[eligible],cash_returns=cash.loc[eligible])
            matchrows.append(dict(rule=rule,mode=mode,**summarize(d,t,cash.loc[eligible])))
            d.to_csv(OUT/f'{rule}-{mode}-daily.csv',index=False)
            # 原策略也从同一天重新入场，比较净值含相同入场/退出费用。
            tar=schedule(closes,rule,eligible)
            actual,_,tt=simulate(opens,closes,rule,eligible[0],eligible[-1],.001,'etf',hours=hours,targets=tar,cash_returns=cash.loc[eligible])
            matchrows.append(dict(rule=rule,mode=mode+'_actual',**summarize(actual,tt,cash.loc[eligible])))
    pd.DataFrame(matchrows).to_csv(OUT/'matched.csv',index=False)
    # 现金零收益敏感性，明确利率代理对结论的影响。
    zeros=[]
    for rule in ['hold','macd_12_26_9','sma200']:
        zero=cash*0
        d,_,t=simulate(opens,closes,rule,index[0],index[-1],.001,'etf',hours=hours,targets=schedule(closes,rule,index),cash_returns=zero)
        zeros.append(dict(rule=rule,**summarize(d,t,zero)))
    pd.DataFrame(zeros).to_csv(OUT/'zero-cash.csv',index=False)
    proof.update(start=str(index[0].date()),through=str(index[-1].date()),bootstrap=intervals,
                 baseline_past20_positive=float((past>0).mean()),
                 code_sha256={str(p.relative_to(ROOT)):sha(p) for p in [Path(__file__),ROOT/'tools/plan_1029_research.py']})
    (OUT/'evidence.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2))
    print('研究输出',OUT,flush=True)


if __name__=='__main__':run()
