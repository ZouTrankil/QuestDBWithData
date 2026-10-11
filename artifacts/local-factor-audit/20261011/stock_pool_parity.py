import json, subprocess, sys
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.research_universe import ResearchUniverse
from quant_platform.research.contracts.common.research_universe import DEFAULT_RESEARCH_UNIVERSES
from quant_platform.research.ports.factor_market_data import FactorMarketInputBundle
root=Path(__file__).resolve().parent
java=Path('F:/repo/engine/data/.gradle/toolchains/temurin-24.0.2/jdk-24.0.2+12/bin')
classes=Path('data-app/build/classes/java/main')
subprocess.run([str(java/'javac.exe'),'-cp',str(classes),'-d',str(root/'exposure-classes'),str(root/'StockPoolProbe.java')],check=True)
symbols=[f'600{i:03}.SH' for i in range(20)]+[f'000{i:03}.SZ' for i in range(10)]+['300001.SZ','301001.SZ','688001.SH','689001.SH','920001.BJ']
dates=pd.bdate_range(end='2026-09-30',periods=260)
records=[]; stock_rows=[]
for i,symbol in enumerate(symbols):
    count=59 if i==0 else 60 if i==1 else 251 if i==30 else 252 if i==31 else 260
    st=1 if i==2 else np.nan if i==3 else 0
    suspended=1 if i==4 else np.nan if i==5 else 0
    tradable=i!=6
    cap=np.nan if i==7 else 0 if i==8 else 1000-i*10
    for date in dates[-count:]: records.append(dict(trade_date=date,ts_code=symbol,total_mv=cap,is_st=st,is_suspended=suspended,is_tradable=tradable,amount=100))
    stock_rows.append([symbol,cap,count,st,suspended,str(tradable).lower()])
market=pd.DataFrame(records)
weights=[]
for definition in DEFAULT_RESEARCH_UNIVERSES:
    if not definition.index_code or definition.universe_id in ['csi800','csi_all']:continue
    for symbol in symbols[::2]:weights.append([definition.index_code,'2026-08-31',symbol])
    # Future constituents must never affect the September snapshot.
    weights.append([definition.index_code,'2026-10-01',symbols[1]])
weights=pd.DataFrame(weights,columns=['index_code','trade_date','ts_code'])
weights.to_csv(root/'pool-indices.tsv',sep='\t',index=False,header=False)
reports=[]
for case in ['ready','insufficient_parent']:
    current=market.copy(); current_stocks=pd.DataFrame(stock_rows)
    if case=='insufficient_parent':
        current.loc[current.ts_code.isin(symbols[:10]),'total_mv']=np.nan
        current_stocks.loc[:9,1]=np.nan
    current_stocks.to_csv(root/'pool-stocks.tsv',sep='\t',index=False,header=False,na_rep='NaN')
    subprocess.run([str(java/'java.exe'),'-cp',str(classes)+';'+str(root/'exposure-classes'),'StockPoolProbe',str(root/'pool-stocks.tsv'),str(root/'pool-indices.tsv'),str(root/'pool-java.tsv'),'2026-09-30'],check=True)
    universe=ResearchUniverse(input_bundle=FactorMarketInputBundle('probe','2025-01-01','2026-09-30',{'market':current,'index_weights':weights}))
    expected={d.universe_id:universe.resolve('2026-09-30',d.universe_id,d.universe_version,'base_ic') for d in DEFAULT_RESEARCH_UNIVERSES}
    compared=0
    for line in (root/'pool-java.tsv').read_text().splitlines():
        c=line.split('\t'); result=expected[c[1]]
        if c[0]=='STATUS':
            assert (c[2]=='true')==(result.status!='unavailable'),(case,c,result.status)
            if result.status=='unavailable':assert c[4]==result.reason,(c,result.reason)
            else:assert c[3]==result.membership_as_of
        else:
            row=result.members.set_index('entity_id').loc[c[2]]
            assert (c[3]=='true')==bool(row.included),(c,row)
            np.testing.assert_allclose(float(c[4]),row.parent_rank_percentile,equal_nan=True)
            assert (c[5].split(',') if c[5] else [])==row.reason_codes,(c,row.reason_codes)
            compared+=1
    reports.append(dict(case=case,pools=len(expected),memberComparisons=compared,mismatches=0))
(root/'stock-pool-parity.json').write_text(json.dumps(reports,indent=2))
print(json.dumps(reports))
