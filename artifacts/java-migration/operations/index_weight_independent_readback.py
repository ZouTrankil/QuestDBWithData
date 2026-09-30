"""Read real XLS/Tushare artifacts independently with pandas/xlrd and compare 11 QuestDB columns."""
from pathlib import Path
import base64, datetime as dt, hashlib, io, json, math, os, re, sqlite3, sys, urllib.parse, urllib.request
import pandas as pd

def content(path):
    p=Path(path).resolve()
    return Path('\\\\?\\'+str(p)).read_bytes()

def micros(value):
    stamp=dt.datetime.fromisoformat(value.replace('Z','+00:00'))
    delta=stamp-dt.datetime(1970,1,1,tzinfo=dt.timezone.utc)
    return (delta.days*86400+delta.seconds)*1_000_000+delta.microseconds

def nullable(value):
    if value is None or pd.isna(value):return None
    text=str(value).strip()
    return text or None

def code(value):
    text=str(value).strip().split('.')[0]
    if not re.fullmatch(r'\d{1,6}',text):raise ValueError('Invalid raw index/code')
    return text.zfill(6)

def constituent(value,exchange=None):
    text=str(value).strip().upper()
    if re.fullmatch(r'\d{6}\.[A-Z]{2,4}',text):return text
    text=code(value);market=(exchange or '').upper()
    suffix='SH' if ('上海' in market or 'SHANGHAI' in market or market in ('SH','SSE')) else 'SZ' if ('深圳' in market or 'SHENZHEN' in market or market in ('SZ','SZSE')) else 'BJ' if ('北京' in market or 'BJ' in market or text[0] in '849') else 'SH' if text[0]=='6' else 'SZ'
    return text+'.'+suffix

def operation_info(base):
    for name in ['source-operation.json','D021-live-acceptance.json','D021-live-acceptance-failure.json']:
        if (base/name).is_file():return json.loads(content(base/name))
    raise ValueError('Operational target evidence required')

def verify(base,run_id):
    con=sqlite3.connect(base/'sync-ledger.sqlite');run=con.execute('select target_id,frozen_json from sync_runs where id=?',(run_id,)).fetchone()
    if run is None:raise ValueError('Saved run required')
    frozen=json.loads(run[1]);params=frozen['parameters']
    if frozen['definition']['jobId']!='data.index_weight' or params['targetId']!=run[0]:raise ValueError('Wrong frozen run')
    table=frozen['definition']['datasetId']
    # The run's physical table is explicitly recorded in the operational report.
    info=operation_info(base);table=info['table']
    if not re.fullmatch(r'java_d021_index_weight_[a-f0-9]{32}',table) or info['targetId']!=run[0]:raise ValueError('Owned target mismatch')
    expected={};groups=set();receipts=[];evidence_root=(base/'sync-evidence').resolve();fields=['index_code','con_code','trade_micros','index_name','index_name_en','con_name','con_name_en','exchange','exchange_en','weight','observed_micros']
    fetched=con.execute("select v.payload_json from sync_events v join sync_entries e on e.id=v.entry_id where e.run_id=? and e.kind='SLICE' and v.state='FETCHED'",(run_id,)).fetchall()
    if len(fetched)!=8:raise ValueError('Eight source scopes required')
    for (payload,) in fetched:
        event=json.loads(payload);p=Path(event['responseEvidence']).resolve()
        if not p.is_relative_to(evidence_root):raise ValueError('Receipt escapes ledger scope')
        data=content(p)
        if len(data)>32*1024*1024 or hashlib.sha256(data).hexdigest()!=event['sourceFingerprint']:raise ValueError('Receipt hash differs')
        receipt=json.loads(data)
        if not receipt['sourceComplete'] or receipt['observedAt']!=params['observedAt']:raise ValueError('Incomplete/different observation')
        rows=[]
        if receipt['sourceKind']=='csindex_oss_xls':
            binary=content(p.parent/receipt['rawFile'])
            if len(binary)>16*1024*1024 or hashlib.sha256(binary).hexdigest()!=receipt['rawSha256']:raise ValueError('XLS hash differs')
            frame=pd.read_excel(io.BytesIO(binary),dtype=object)
            if len(frame.columns)!=10:raise ValueError('XLS width differs')
            accepted_headers=[['日期','指数代码','指数名称','指数英文名称','成分券代码','成分券名称','成分券英文名称','交易所','交易所英文名称','权重'],['日期Date','指数代码 Index Code','指数名称 Index Name','指数英文名称Index Name(Eng)','成份券代码Constituent Code','成份券名称Constituent Name','成份券英文名称Constituent Name(Eng)','交易所Exchange','交易所英文名称Exchange(Eng)','权重(%)weight']]
            if [str(x).strip() for x in frame.columns] not in accepted_headers:raise ValueError('XLS headers differ')
            for values in frame.itertuples(index=False,name=None):
                if all(pd.isna(x) for x in values):continue
                date,index,iname,ienglish,stock,sname,senglish,exchange,eenglish,weight=values
                date=str(date).replace('-','')[:8];day=dt.datetime.strptime(date,'%Y%m%d').replace(tzinfo=dt.timezone.utc)
                rows.append([code(index),constituent(stock,nullable(exchange)),micros(day.isoformat()),nullable(iname),nullable(ienglish),nullable(sname),nullable(senglish),nullable(exchange),nullable(eenglish),float(weight),micros(receipt['observedAt'])])
        elif receipt['sourceKind']=='tushare':
            enrichment=receipt['nameEnrichment'];refs=enrichment['referenceRows']
            encoded=json.dumps(refs,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
            if hashlib.sha256(encoded).hexdigest()!=enrichment['referenceFingerprint'] or enrichment['targetId']!=params['stockDetailTargetId']:raise ValueError('Name reference evidence differs')
            names={r['ts_code']:r['name'] for r in refs}
            if len(names)!=len(refs):raise ValueError('Duplicate name reference')
            raw=receipt['rawRows'];latest=max((r['trade_date'] for r in raw),default=None)
            for r in raw:
                if receipt['purpose']=='latest_snapshot' and r['trade_date']!=latest:continue
                day=dt.datetime.strptime(r['trade_date'],'%Y%m%d').replace(tzinfo=dt.timezone.utc);stock=constituent(r['con_code'])
                rows.append([code(r['index_code']),stock,micros(day.isoformat()),None,None,names.get(stock),None,None,None,float(r['weight']),micros(receipt['observedAt'])])
        else:raise ValueError('Unknown provider route')
        if len(rows)!=receipt['returnedRows'] or len(rows)!=event['returnedRows']:raise ValueError('Source counts differ')
        for row in rows:
            key=tuple(row[:3]);groups.add((row[0],row[2]))
            if key in expected or not math.isfinite(row[9]):raise ValueError('Duplicate key/nonfinite weight')
            expected[key]=row
        receipts.append({'file':str(p),'rows':len(rows),'sha256':event['sourceFingerprint']})
    sql=f'SELECT index_code,con_code,cast(trade_date AS long) AS trade_micros,index_name,index_name_en,con_name,con_name_en,exchange,exchange_en,weight,cast(update_time AS long) AS observed_micros FROM {table} ORDER BY index_code,trade_date,con_code LIMIT 500001'
    request=urllib.request.Request('http://127.0.0.1:9000/exec?'+urllib.parse.urlencode({'query':sql}))
    token=base64.b64encode((os.environ['APP_QUESTDB_USERNAME']+':'+os.environ['APP_QUESTDB_PASSWORD']).encode()).decode();request.add_header('Authorization','Basic '+token)
    with urllib.request.urlopen(request,timeout=30) as response:result=json.load(response)
    if 'error' in result:raise ValueError('QuestDB query rejected')
    raw_actual=result['dataset']
    if len(raw_actual)>500000:raise ValueError('Target bound exceeded')
    actual={};duplicates=0
    for row in raw_actual:
        if frozen['mode']=='SNAPSHOT':inside=(row[0],row[2]) in groups
        else:inside=micros(frozen['from']+'T00:00:00+00:00')<=row[2]<micros((dt.date.fromisoformat(frozen['to'])+dt.timedelta(days=1)).isoformat()+'T00:00:00+00:00')
        if not inside:continue
        key=tuple(row[:3]);duplicates+=key in actual;actual[key]=row
    missing=set(expected)-set(actual);extra=set(actual)-set(expected);wrong=[k for k in set(expected)&set(actual) if expected[k]!=actual[k]]
    output={'task':'D021','runId':run_id,'table':table,'targetId':run[0],'status':'MATCHED' if not(missing or extra or wrong or duplicates) else 'MISMATCHED','fieldsCompared':fields,'expectedRows':len(expected),'actualRows':len(actual),'missingRows':len(missing),'unexpectedRows':len(extra),'mismatchedRows':len(wrong),'duplicateTargetKeys':duplicates,'mismatchSample':[{'key':k,'expected':expected[k],'actual':actual[k]} for k in wrong[:3]],'sourceReceipts':receipts,'independentImplementation':'Python pandas/xlrd source binary parsing and raw Tushare/D002 reference rows; no production mapper or normalizedRows used','query':sql}
    path=base/('D021-python-independent-'+run_id+'.json');path.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');return output

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8');base=Path(sys.argv[1]);info=operation_info(base);result=verify(base,sys.argv[2] if len(sys.argv)>2 else (info['run'] if 'run' in info else info['runs'][-1])['runId']);print(json.dumps({k:result[k] for k in ['status','expectedRows','actualRows','missingRows','unexpectedRows','mismatchedRows','duplicateTargetKeys']}));sys.exit(0 if result['status']=='MATCHED' else 1)
