import sys,json,time
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,r'F:\repo\engine\back-monitor\src')
from quant_platform.research.engines.factor_platform.factor_neutralization import _build_projector
root=Path('artifacts/local-factor-audit/20261011')
sample=pd.read_csv(root/'projection-parity.csv')
design,projector=_build_projector(sample)
y=sample['value'].to_numpy(float)
def compute():
    residual=y-design@(projector@y)
    return residual/np.std(residual)
expected=compute(); actual=sample['result'].to_numpy(float)
np.testing.assert_allclose(actual,expected,rtol=1e-10,atol=1e-10)
for _ in range(100):compute()
rounds=[]
for _ in range(7):
    start=time.perf_counter()
    for i in range(200):compute()
    rounds.append((time.perf_counter()-start)*1000/200)
report={'sample_rows':len(sample),'industries':sample.industry.nunique(),'max_absolute_error':float(np.max(np.abs(actual-expected))),'parity':'PASS','python_cached_projection_median_ms':float(np.median(rounds)),'python_cached_projection_rounds_ms':rounds,'scope':'Synthetic neutralization kernel only. Python timing excludes DataFrame sample filtering/sorting/cache lookup; Java timing includes them. Not full pipeline speedup evidence.'}
(root/'projection-parity-report.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
