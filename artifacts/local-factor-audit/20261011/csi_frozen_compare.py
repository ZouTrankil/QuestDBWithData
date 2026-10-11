import json,sys,time
from pathlib import Path
import pandas as pd
import numpy as np
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_style_benchmarks import build_etf_style_benchmarks
root=Path(__file__).parent
data=json.loads((root/(sys.argv[1] if len(sys.argv)>1 else 'csi-frozen-inputs.json')).read_text(encoding='utf-8'))
def panel(name):
    parts=[]
    for security in data[name]['securities']:
        frame=pd.DataFrame(security['columns']);frame['trade_date']=pd.to_datetime(security['dates']);frame['ts_code']=security['symbol']
        for col in security['columns']:frame[col]=pd.to_numeric(frame[col],errors='coerce')
        parts.append(frame)
    return pd.concat(parts,ignore_index=True)
def financial(table,fields):
    rows=[]
    for symbol,events in data['financial']['tables'][table].items():
        for event in events:rows.append([symbol,event['availableDate'],*event['values']])
    frame=pd.DataFrame(rows,columns=['ts_code','ann_date',*fields])
    for field in fields:frame[field]=pd.to_numeric(frame[field],errors='coerce')
    return frame
holdings=pd.DataFrame(data['holdings']['history']['rows']).rename(columns={'etf':'ts_code','stock':'symbol','marketValueRatio':'stk_mkv_ratio','marketValue':'mkv','announced':'ann_date','periodEnd':'end_date'})
for col in ['stk_mkv_ratio','mkv']:holdings[col]=pd.to_numeric(holdings[col],errors='coerce')
frames={'stock_market':panel('market'),'stock_daily_basic':panel('basic'),'constituents_history':holdings,
        'stock_financials':financial('fina_indicator',['roe','grossprofit_margin','ocf_to_or','debt_to_assets','q_gr_yoy','q_profit_yoy']),
        'stock_cashflow':financial('cashflow',['n_cashflow_act'])}
etf_market=pd.DataFrame({'ts_code':data['validEtfs'],'trade_date':pd.Timestamp('2026-09-30')})
start=time.perf_counter()
expected=build_etf_style_benchmarks(frames,etf_market=etf_market,as_of='2026-09-30',output_dates={pd.Timestamp('2026-09-30')}).etf_values
seconds=time.perf_counter()-start
actual=pd.DataFrame(data['java']).rename(columns={'date':'trade_date','etf':'ts_code','factor':'factor_id','componentCount':'component_count','announced':'constituent_ann_date','periodEnd':'constituent_end_date'})
keys=['trade_date','ts_code','factor_id']
for frame in [actual,expected]:frame.trade_date=pd.to_datetime(frame.trade_date)
actual=actual.set_index(keys).sort_index();expected=expected.set_index(keys).sort_index()
assert list(actual.index)==list(expected.index)
bad={}
for col in ['value','coverage','component_count']:
    a=pd.to_numeric(actual[col],errors='coerce').to_numpy(float);b=pd.to_numeric(expected[col],errors='coerce').to_numpy(float)
    bad[col]=int((~np.isclose(a,b,rtol=1e-9,atol=1e-10,equal_nan=True)).sum())
for col in ['constituent_ann_date','constituent_end_date']:
    bad[col]=int((pd.to_datetime(actual[col]).to_numpy()!=pd.to_datetime(expected[col]).to_numpy()).sum())
result={'rows':len(actual),'mismatches':bad,'pythonComputeSeconds':seconds,'scope':'Same frozen Java source projections; source extraction and full-history refresh statistics require separate audits'}
result['javaComputeSeconds']=data.get('javaComputeSeconds')
(root/(sys.argv[2] if len(sys.argv)>2 else 'csi-frozen-parity.json')).write_text(json.dumps(result,indent=2));print(json.dumps(result));assert not any(bad.values())
