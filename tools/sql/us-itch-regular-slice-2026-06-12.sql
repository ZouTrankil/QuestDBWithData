-- 正常时段有限片段，纳秒按整数文本返回，避免前端浮点截断。不是全日订单簿。
select cast(cast(ts as long) as string) as ts_ns,message_index,message_type,stock_locate,
       tracking_number,symbol,order_ref,new_order_ref,side,shares,price_4,
       raw_hex,decompressed_offset,source_sha256,source_complete,observed_at
from us_nasdaq_itch_messages
where source_sha256='a5a050723c9ca9c4e84be02dc2664898c207441043c69952b781a61f483c2dfa'
  and message_index>=60936546 and message_index<61036546
order by ts,source_sha256,message_index
limit 100001;

-- 执行消息的空价格保留，不能拿这列直接计算整个片段的成交 VWAP。
select message_type,count() as rows,sum(shares) as reported_shares
from us_nasdaq_itch_messages
where source_sha256='a5a050723c9ca9c4e84be02dc2664898c207441043c69952b781a61f483c2dfa'
  and message_index>=60936546 and message_index<61036546
group by message_type order by message_type;

select count() as rows,cast(min(cast(ts as long)) as string) as first_ns,
       cast(max(cast(ts as long)) as string) as last_ns
from us_nasdaq_itch_messages
where source_sha256='a5a050723c9ca9c4e84be02dc2664898c207441043c69952b781a61f483c2dfa'
  and message_index>=60936546 and message_index<61036546;

-- 原盘前范围仍独立保留，不能按相同文件日期合并成连续全日。
select count() as rows,cast(min(cast(ts as long)) as string) as first_ns,
       cast(max(cast(ts as long)) as string) as last_ns
from us_nasdaq_itch_messages
where source_sha256='fdf8133e8aa56e888a57dcdc9048142890a7bac346dee158f16fee233c2c396e'
  and message_index>=309047 and message_index<659883;

select name,writerTxn,sequencerTxn,bufferedTxnSize,suspended
from wal_tables() where name='us_nasdaq_itch_messages';

select table_name,id,directoryName from tables()
where table_name='us_nasdaq_itch_messages';
