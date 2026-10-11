import json
from pathlib import Path
import numpy as np
import pandas as pd
root=Path(__file__).resolve().parent
members=pd.DataFrame(json.loads((root/'etf-constituents-live.json').read_text())['members'])
stocks=pd.DataFrame(json.loads((root/'full-panel-java-values-r3.json').read_text()))
joined=stocks[['ts_code','ret_1','ret_5']].merge(members,left_on='ts_code',right_on='stock')
joined['valid']=joined.weight.where(joined.ret_1.notna(),0)
joined['up']=joined.valid*joined.ret_1.gt(0)
expected=joined.groupby('etf').agg(component_up_weight=('up','sum'),component_valid_weight=('valid','sum'),component_median_return_5d=('ret_5','median'),component_return_dispersion_5d=('ret_5','std'),component_count=('stock','nunique'))
expected['component_up_breadth_1d']=expected.component_up_weight/expected.component_valid_weight.where(expected.component_valid_weight.gt(0))
expected['component_top10_weight']=members.sort_values(['etf','weight'],ascending=[True,False]).groupby('etf').head(10).groupby('etf').weight.sum()
rows=json.loads((root/'etf-component-frozen.json').read_text());actual=pd.DataFrame([dict(etf=r['etf'],**r['columns']) for r in rows]).set_index('etf')
assert set(actual.index)==set(expected.index);expected=expected.reindex(actual.index)
bad=0;checks=0
for c in expected:
 a=pd.to_numeric(actual[c],errors='coerce').to_numpy(float);b=expected[c].to_numpy(float)
 bad+=int((~np.isclose(a,b,rtol=1e-10,atol=1e-10,equal_nan=True)).sum());checks+=len(a)
result=dict(etfs=len(actual),checks=checks,mismatches=bad,source='frozen actual holdings and previously verified stock returns',scope='constituent aggregation only; no L2 or source-reader parity')
(root/'etf-component-frozen-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert bad==0
