# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=2,<3", "pyarrow>=18", "PyYAML>=6", "duckdb>=1.2", "pytz>=2024.1"]
# ///
"""验收固定 BTC 批次的修订准入、同步关联和空 Schema；不写 QuestDB。"""
import argparse
from datetime import date, datetime, timedelta
import json
from pathlib import Path
import duckdb
import btc_research_data as btc


def validate(root, check_empty_source=False):
    batch = json.loads((root/'current.json').read_text())['batchId']
    manifest = json.loads((root/'batches'/batch/'manifest.json').read_text())
    quality = json.loads((root/'batches'/batch/'quality.json').read_text())
    if manifest['ruleVersion'] < 3 or set(manifest['members']) != set(btc.DATASETS):
        raise ValueError('验收要求完整六组及质量规则 v3')
    results = {d:btc.read_batch(root,d,batch_id=batch,mode='explore') for d in btc.DATASETS}
    if not all(r['formalVerified'] for r in results.values()):
        raise ValueError('批次尚未全部通过正式认证')
    expected = {d:m['formalEvidence']['expectedRows'] for d,m in manifest['members'].items()}
    actual = {d:r['actualRows'] for d,r in results.items()}
    if expected != actual:
        raise ValueError('批次行数与原冻结来源键集合不同')
    association = quality['fundingPriceAssociations']
    observed_times={time.isoformat() for time in results['funding']['table']['funding_time'].to_pylist()}
    associated_times=[event['fundingTime'] for event in association['events']]
    if len(associated_times)!=len(observed_times) or set(associated_times)!=observed_times or association['completeEvents']+association['incompleteEvents']!=len(observed_times):
        raise ValueError('同步关联没有逐事件覆盖整份资金费率输入')
    complete = next((event for event in association['events'] if event['complete']),None)
    incomplete = next((event for event in association['events'] if not event['complete']),None)
    if complete is None:
        raise ValueError('范围内没有可验收的完整同步事件')
    def scope(event):
        lower=datetime.fromisoformat(event['fundingTime'])
        return {'lower':lower,'until':lower+timedelta(microseconds=1),'batch_id':batch,'columns':['funding_time','funding_rate'],'purpose':'funding_synchronous'}
    good=btc.read_batch(root,'funding',**scope(complete))
    if good['actualRows']!=1 or not good['associations'][0]['complete']:
        raise ValueError('同步关联成功样本读取错误')
    rejected=None
    if incomplete:
        try:
            btc.read_batch(root,'funding',**scope(incomplete))
        except ValueError as error:
            if '质量规则拒绝读取' not in str(error):raise
            rejected=True
        else:raise ValueError('同步缺价事件未被严格模式拒绝')
        explored=btc.read_batch(root,'funding',mode='explore',**scope(incomplete))
        if explored['associations'][0]['complete'] or not explored['issues']:
            raise ValueError('探索读取未保留关联缺项')
    first=datetime.fromisoformat(manifest['from'])
    empty=btc.read_batch(root,'spot_1d',batch_id=batch,lower=first,until=first,columns=['open_time','close'])
    if empty['actualRows'] or empty['resultState']!='EMPTY_RESULT' or empty['table'].column_names!=['open_time','close']:
        raise ValueError('空读取未保留约定列')
    if empty['table'].schema.field('open_time').type.tz!='UTC':
        raise ValueError('空读取丢失 UTC 类型')
    connection=duckdb.connect()
    try:
        connection.register('funding_event',good['table'])
        duck_count=connection.sql('SELECT count(*) FROM funding_event').fetchone()[0]
    finally:connection.close()
    result={'batchId':batch,'from':manifest['from'],'until':manifest['until'],'ruleVersion':manifest['ruleVersion'],
            'rows':actual,'expectedSourceKeys':expected,'formalVerified':True,'completeSynchronousEvents':association['completeEvents'],
            'incompleteSynchronousEvents':association['incompleteEvents'],'strictIncompleteEventRejected':rejected,
            'completeEventReadable':good['actualRows']==1,'typedEmptyResult':True,'duckdbRows':duck_count,'historicalPit':False}
    if check_empty_source:
        endpoint,auth=btc.configured_endpoint()
        empty_source=btc.BtcReader(endpoint,auth).read('spot_1d',date(1900,1,1),date(1900,1,1))
        if empty_source.num_rows or 'open_time' not in empty_source.column_names or empty_source.schema.field('open_time').type.tz!='UTC':
            raise ValueError('数据库空查询未保留实际 UTC Schema')
        result['emptyRemoteSchema']={'rows':empty_source.num_rows,'columns':empty_source.column_names,'utc':True}
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=None)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--check-empty-source',action='store_true')
    args=parser.parse_args()
    root=args.root or btc.configured_writer_root()/'research'
    result=validate(root,args.check_empty_source)
    btc._atomic_json(args.output,result)
    print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':main()
