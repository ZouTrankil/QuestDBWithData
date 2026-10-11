import sqlite3,json
from pathlib import Path
c=sqlite3.connect(Path('var/sync-ledger.sqlite3').resolve().as_uri()+'?mode=ro',uri=True);c.row_factory=sqlite3.Row
rows=c.execute("select r.id,e.state,e.updated_at from sync_runs r join sync_entries e on r.id=e.id where r.job_id='data.factor_observation' order by e.updated_at desc limit 4").fetchall()
result=[]
for row in rows:
 r=dict(row);r['slices']=[dict(x) for x in c.execute("select state,count(*) pages from sync_entries where run_id=? and kind='SLICE' group by state",(r['id'],))];result.append(r)
print(json.dumps(result,indent=2))
