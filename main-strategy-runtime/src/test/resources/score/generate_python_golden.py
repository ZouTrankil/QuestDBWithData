"""Offline oracle only. Java production never imports or invokes Python.

Run with back-monitor's pinned environment and --source-root back-monitor/src.
Records values from the original promoted daily functions, including sparse chip
rows, ST/suspension guards, announcement-over-report-date PIT, and active LOF.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import sys
import warnings

parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=Path, required=True)
args = parser.parse_args()
sys.path.insert(0, str(args.source_root))
import numpy as np
import pandas as pd
from quant_platform.research.panels import stock_l2, component_risk, etf_components, etf_market, chip_components
from quant_platform.research.panels.l2_scorer import build_combo_ready_panel
from quant_platform.research.scoring import stock_guards, l2_features, l2_regime, etf_factor_weights
from quant_platform.research.contracts.etf.common.etf_weighting import WeightSpec
from quant_platform.research.contracts.l2.l2_scorer import POSITIVE_BASE_WEIGHTS, RISK_BASE_WEIGHTS
from quant_platform.research.frameops.ranking import rank_pct_by_date
warnings.filterwarnings("ignore", category=FutureWarning)

dates = pd.bdate_range("2026-01-02", periods=160)
signals = dates[-6:]
symbols = [f"{600001+s:06d}.SH" for s in range(12)]
stock = []
for s, symbol in enumerate(symbols):
    previous = None
    for t, date in enumerate(dates):
        price = (10+s) * (1 + .00035*s*t + (.035+.0008*s)*math.sin(1.7*t+.4*s))
        ret = None if previous is None else price/previous-1
        stock.append(dict(trade_date=date, ts_code=symbol, price=price, raw_close=price,
            amount=20000000+s*1600000+t*5000, turnover_rate=1.3+.2*s+.03*(t%7), pb=1+.13*s,
            up_limit=price*1.1 if not (s==2 and t%23==0) else price,
            down_limit=price*.9, is_st=int(s==0), is_suspended=int(s==1 and t==159), ret=ret))
        previous = price
daily = pd.DataFrame(stock)
raw = []
for t, date in enumerate(signals):
    for s, symbol in enumerate(symbols):
        x = ((s*7+t*3)%12)+1
        raw.append(dict(trade_date=date,symbol=symbol,clean_amount=1000+80*s,q2_count=x,
            q2_accumulation=x*30,q2_accumulation_ratio=x/20,active_buy_ratio=.2+x/30,
            gmm_main_force_net_inflow=(x-5)*20,main_funds_buy_amount=x*100000,
            q3_count=13-x,q4_count=(s+t)%5,q4_distribution=(s+t)%4*10,
            q4_distribution_ratio=((s+t)%7)/8,active_sell_ratio=(13-x)/20,
            large_active_sell_amount=(13-x)*10,obi_top5_std=x/15,orderbook_replenish_speed=x*2,
            depth_slope=x/5,continuous_auction_ratio=.2+x/25,close_auction_net_inflow=(x-4)*8,
            open_close_vol_ratio=x/7,depth_top5_mean=(13-x)*15,ofi_persistence=(13-x)/12,
            ofi_std=(s+t)%8,mfi_escort=(13-x)/11,mean_obi_top5=(13-x)/20))
l2 = pd.DataFrame(raw)
metadata = pd.DataFrame([dict(symbol=symbol,name="ST sample" if s==0 else f"Stock {s}",industry_l2=f"I{s%3}") for s,symbol in enumerate(symbols)])
chip = pd.DataFrame([dict(trade_date=date,symbol=symbol,l2_activity_score=(s+t)%10/10,
    l2_chase_risk_score=((s*3+t)%10)/10,l2_breakdown_risk_score=((s*5+t)%9)/9,
    l2_activity_top20_flag=s>=7,l2_chase_risk_top20_flag=(s+t)%5==0,l2_breakdown_top10_flag=(s+t)%7==0)
    for t,date in enumerate(signals) for s,symbol in enumerate(symbols) if s<9])
sentiment_raw = pd.DataFrame([dict(trade_date=date,sentiment_score=float((t*3)%7),sentiment_state="source-state") for t,date in enumerate(signals)])
sentiment = etf_market.prepare_market_sentiment(sentiment_raw,signals[0],signals[-1])
cutoff = dates[-7]
l2_weights = pd.DataFrame([dict(trade_date=cutoff,**{f"w_{name}":weight*.7 for name,weight in POSITIVE_BASE_WEIGHTS.items()},
    **{f"risk_active_{name}":float(i%2==0) for i,name in enumerate(RISK_BASE_WEIGHTS)})])
aligned_l2 = pd.concat([l2_weights.assign(trade_date=date) for date in signals],ignore_index=True)
scored = stock_l2.score_l2_panel(l2,stock_l2.build_daily_diagnostics(daily),metadata,min_l2_prob_acc=.30,max_l2_prob_risk=.60)
scored = stock_guards.add_sentiment_crowding_penalty(scored,daily,sentiment,penalty_weight=.10,q4_intensity=0,q5_intensity=1)
scored = stock_guards.add_stock_guard_features(scored,daily)
scored = stock_guards.attach_chip_action_scores(scored,chip)
scored = l2_features.add_scorer_factor_columns(l2_features.enrich_l2_scores(scored,l2))
scored = l2_regime.apply_scorers(scored,aligned_l2)
stock_signal = etf_components.build_stock_signal_matrix(l2_regime.build_l2_scores_for_scorer(scored,"feature_rank_v2_dynamic"))
stock_signal = stock_signal.merge(component_risk.build_stock_component_risk_features(daily),on=["trade_date","symbol"],how="left")
basic_raw = pd.DataFrame([
    dict(ts_code="510001.SH",name="沪深300ETF",status="L",market="E",fund_type="股票型",invest_type="指数型",benchmark="沪深300",m_fee=.3,c_fee=.1),
    dict(ts_code="510002.SH",name="芯片ETF",status="L",market="E",fund_type="股票型",invest_type="指数型",benchmark="芯片",m_fee=.5,c_fee=.1),
    dict(ts_code="510003.SH",name="红利ETF",status="L",market="E",fund_type="股票型",invest_type="指数型",benchmark="红利",m_fee=None,c_fee=None),
    dict(ts_code="160001.SZ",name="成长混合LOF",status="L",market="E",fund_type="混合型",invest_type="灵活配置",benchmark="成长",m_fee=.8,c_fee=.2),
    dict(ts_code="510099.SH",name="纳指ETF",status="L",market="E",fund_type="股票型",invest_type="指数型",benchmark="纳指",m_fee=.5,c_fee=.1)])
basic = etf_market.prepare_etf_reference(basic_raw,include_active_lof=True)
etf_factor = []
for e,code in enumerate(basic.ts_code):
    previous = None
    for t,date in enumerate(dates):
        close=(1+e*.8)*(1+.001*(e+1)*t+.022*math.sin(.61*t+e))
        etf_factor.append(dict(trade_date=date,ts_code=code,close=close,open=close*(.998+e*.0003),
            pct_change=None if previous is None else (close/previous-1)*100,amount=1600+e*300+(t%5)*25,
            ma_bfq_20=close*.98,ma_bfq_60=close*.96,ma_bfq_250=close*.9,rsi_bfq_6=42+e*7+(t%9)))
        previous=close
etf_factor=pd.DataFrame(etf_factor)
portfolio=[]
for e,code in enumerate(basic.ts_code):
    for batch,(ann,period) in enumerate([(signals[0]-pd.Timedelta(days=20),pd.Timestamp("2025-12-31")),
            (signals[2],pd.Timestamp("2025-09-30")),(signals[-1]+pd.Timedelta(days=1),pd.Timestamp("2026-03-31"))]):
        for s,symbol in enumerate(symbols if e<3 else symbols[:4]):
            portfolio.append(dict(ts_code=code,symbol=symbol,ann_date=ann,end_date=period,
                stk_mkv_ratio=float(1+(s+e+batch*3)%12),mkv=float(1000+s*100)))
portfolio=pd.DataFrame(portfolio)
prepared=etf_components.prepare_etf_portfolio(portfolio,signals[-1],set(basic.ts_code))
technical=etf_market.add_etf_technical_features(etf_factor)
etf_scores,coverage=etf_components.build_daily_etf_scores(list(signals),technical,basic,prepared,stock_signal,
    min_component_hits=10,min_amount=1000,liquidity_window=3,liquidity_min_periods=1,min_buy_breadth=.25,min_top100_weight=.4)
panel=chip_components.add_chip_action_component_scores(build_combo_ready_panel(etf_scores,"feature_rank_v2_dynamic"),chip)
for name,spec in etf_factor_weights.FACTOR_SPECS.items():
    panel[f"{name}_rank"]=rank_pct_by_date(panel,spec["column"],ascending=spec["direction"]>0).fillna(.5)
factor_weights=pd.DataFrame([dict(trade_date=cutoff,dynamic_evidence_sum=2.5,
    **{f"w_{name}":(i+1)/120 for i,name in enumerate(etf_factor_weights.FACTOR_SPECS)})])
factor_states=pd.DataFrame([dict(trade_date=cutoff,factor="component_l2",state="frozen-test",n=40)])
overlay_weights=pd.concat([factor_weights.assign(trade_date=date) for date in signals],ignore_index=True)
overlay_weights["trade_date"]=overlay_weights["trade_date"].dt.date
panel=etf_factor_weights.apply_dynamic_overlay(panel,etf_factor_weights.apply_static_score(panel,WeightSpec("oracle",.55,.2,.2,.05,2,.5)),overlay_weights,.10)

def clean(value):
    if isinstance(value,(pd.Timestamp,np.datetime64)) or value.__class__.__name__ == "date":return pd.Timestamp(value).strftime("%Y-%m-%d")
    if isinstance(value,(np.bool_,)):return bool(value)
    if isinstance(value,(np.integer,)):return int(value)
    if isinstance(value,float) and not math.isfinite(value):return None
    if isinstance(value,np.floating):return clean(float(value))
    if isinstance(value,(set,frozenset)):return sorted(value)
    if isinstance(value,dict):return {key:clean(v) for key,v in value.items()}
    if isinstance(value,list):return [clean(v) for v in value]
    return value
def records(frame):return clean(frame.to_dict("records"))
files=["panels/stock_l2.py","scoring/stock_guards.py","scoring/l2_features.py","scoring/l2_regime.py","panels/etf_components.py",
    "panels/etf_score_stages.py","panels/etf_market.py","panels/chip_components.py","scoring/etf_factor_weights.py"]
fixture=dict(oracle=dict(pandas=pd.__version__,numpy=np.__version__,source_sha256={name:hashlib.sha256((args.source_root/"quant_platform/research"/name).read_bytes()).hexdigest() for name in files}),
    signal_dates=clean(list(signals)),training_cutoff=clean(cutoff),stock_daily=records(daily),l2_raw=records(l2),stock_metadata=records(metadata),
    chip=records(chip),sentiment_raw=records(sentiment_raw),sentiment=records(sentiment),etf_basic_raw=records(basic_raw),etf_reference=records(basic),
    etf_factor=records(etf_factor),holdings=records(portfolio),factor_weights=records(factor_weights),factor_states=records(factor_states),l2_weights=records(l2_weights),
    expected_features=records(scored),expected_stock=records(stock_signal),expected_panel=records(panel),expected_coverage=records(coverage))
output=Path(__file__).with_name("python-promoted-golden.json")
output.write_text(json.dumps(fixture,ensure_ascii=False,separators=(",",":"),allow_nan=False),encoding="utf-8")
for role,frame in [("factor_weights",factor_weights),("factor_states",factor_states),("l2_factor_weights",l2_weights)]:
    frame.to_parquet(Path(__file__).with_name(role+".parquet"),index=False)
print(f"golden={output}; stock={len(stock_signal)} panel={len(panel)} coverage={len(coverage)}")
