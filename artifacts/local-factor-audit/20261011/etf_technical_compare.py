import json,sys,requests
from pathlib import Path
import pandas as pd
import numpy as np
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_maturity import _factor_rows
root=Path(__file__).parent
report=json.loads((root/'etf-command-csi-window-panel.json').read_text(encoding='utf-8'))
def fetch(sql):
    response=requests.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,100001'},timeout=60)
    response.raise_for_status();data=response.json();assert 'error' not in data,data
    assert len(data['dataset'])<100001
    return pd.DataFrame(data['dataset'],columns=[c['name'] for c in data['columns']])
state="SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name='etf_factor'"
before=fetch(state);assert before.to_dict('records')==report['technicalSourceState']
source=fetch("SELECT trade_date,ts_code,rsi_bfq_6 FROM etf_factor WHERE trade_date>='2026-09-30' AND trade_date<'2026-10-01'")
assert before.equals(fetch(state))
market=pd.DataFrame([(date,s['symbol']) for s in report['securities'] for date in s['dates']],columns=['trade_date','ts_code'])
source.trade_date=pd.to_datetime(source.trade_date).dt.tz_localize(None).dt.normalize()
market.trade_date=pd.to_datetime(market.trade_date)
expected=_factor_rows(source.merge(market,on=['trade_date','ts_code'],how='inner'),'technical_rsi_6d',value_column='rsi_bfq_6')
actual=pd.DataFrame(report['technical']).rename(columns={'symbol':'ts_code','date':'trade_date','technical_rsi_6d':'value'})
actual.trade_date=pd.to_datetime(actual.trade_date)
expected=expected.set_index(['trade_date','ts_code']).sort_index();actual=actual.set_index(['trade_date','ts_code']).sort_index()
assert list(expected.index)==list(actual.index)
bad=int((~np.isclose(expected.value.to_numpy(float),actual.value.to_numpy(float),rtol=1e-12,atol=1e-12,equal_nan=True)).sum())
result={'rows':len(actual),'mismatches':bad,'sourceRows':len(source),'sourceStateMatched':True,'pythonFunction':'_factor_rows'}
(root/'etf-technical-live-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert bad==0
