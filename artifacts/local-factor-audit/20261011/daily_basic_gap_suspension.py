import sys,json,requests
from pathlib import Path
import pandas as pd
sys.path.insert(0,r'F:\repo\engine\back-monitor\src')
from quant_platform.data.contracts.bse_code_mapping import canonical_stock_code
p=Path('artifacts/local-factor-audit/20261011');gap=pd.read_json(p/'daily-basic-gap-keys.json');gap.trade_date=pd.to_datetime(gap.trade_date).dt.normalize()
s=requests.Session()
def query(sql):
 r=s.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,300001'},timeout=60);r.raise_for_status();d=r.json();assert 'error' not in d,d;assert len(d['dataset'])<300001;return pd.DataFrame(d['dataset'],columns=[c['name'] for c in d['columns']])
f=query("select timestamp,ts_code,is_suspended from stk_suspend where timestamp>='2025-06-27' and timestamp<'2026-10-01' and is_suspended=1")
f['trade_date']=pd.to_datetime(f.timestamp).dt.tz_localize(None).dt.normalize();f.ts_code=f.ts_code.map(canonical_stock_code)
keys=set(zip(f.ts_code,f.trade_date));gap['suspensionRecorded']=[(a,b) in keys for a,b in zip(gap.ts_code,gap.trade_date)]
remaining=gap[~gap.suspensionRecorded];gap.to_json(p/'daily-basic-gap-suspension-keys.json',orient='records',date_format='iso')
r={'missingBasicRows':len(gap),'matchedSuspensionRows':int(gap.suspensionRecorded.sum()),'withoutSuspensionRecord':len(remaining),'remainingBySymbol':remaining.groupby('ts_code').size().sort_values(ascending=False).to_dict(),'remainingExamples':remaining.head(20).to_dict('records'),'scope':'Local suspension-record correlation only; does not establish provider omission policy or certify factor input completeness.'}
(p/'daily-basic-gap-suspension.json').write_text(json.dumps(r,indent=2,default=str));print(json.dumps(r,default=str))
