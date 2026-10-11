import requests,json,datetime,calendar
from pathlib import Path
root=Path(__file__).parent
session=requests.Session()
def query(sql):
    response=session.get("http://localhost:9000/exec",params={"query":sql},timeout=90);response.raise_for_status();data=response.json()
    if "error" in data: raise RuntimeError(data["error"])
    return data["dataset"]
state_sql="SELECT id,table_name,directoryName,table_txn,wal_txn FROM tables() WHERE table_name IN ('backtest_daily','v_backtest_daily') ORDER BY table_name"
before=query(state_sql)
fields="ts_code,trade_date,close,open,high,low,amount,vol,up_limit,down_limit,is_st,is_suspended"
start=datetime.date(2025,6,27);end=datetime.date(2026,10,1);periods=[]
while start<end:
    stop=min(end,(start.replace(day=28)+datetime.timedelta(days=4)).replace(day=1))
    frames=[]
    for table in ["backtest_daily","v_backtest_daily"]:
        rows=query(f"SELECT {fields} FROM {table} WHERE trade_date>='{start}' AND trade_date<'{stop}' ORDER BY ts_code,trade_date LIMIT 200001")
        assert len(rows)<=200000
        keys=[tuple(x[:2]) for x in rows];assert len(keys)==len(set(keys))
        frames.append(rows)
    same=frames[0]==frames[1]
    periods.append({"from":str(start),"throughExclusive":str(stop),"rows":[len(x) for x in frames],"exactMatch":same})
    print(json.dumps(periods[-1]),flush=True);start=stop
result={"fields":fields.split(','),"periods":periods,"before":before,"after":query(state_sql)}
result['passed']=result['before']==result['after'] and all(x['exactMatch'] for x in periods)
(root/'backtest-view-source-parity.json').write_text(json.dumps(result,indent=2))
assert result['passed']
print('Source parity passed',flush=True)
