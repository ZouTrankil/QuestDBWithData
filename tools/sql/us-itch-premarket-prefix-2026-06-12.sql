-- 仅 Nasdaq 原始 gzip 前缀中连续的订单流，不代表完整日或正常交易时段。
-- ts_ns 保留全部纳秒；某些 PGWire 客户端会把 ts 读成微秒，使用整数列复算。
-- E 消息本身没有价格，price_usd 保持空值；成交价需从已验证的原订单引用取得。
-- P 的 side 通常固定为 B，不能当作主动买卖方向。
SELECT
    ts,
    CAST(ts AS LONG) AS ts_ns,
    message_index,
    message_type,
    stock_locate,
    tracking_number,
    symbol,
    order_ref,
    new_order_ref,
    side,
    shares,
    price_4,
    CAST(price_4 AS DOUBLE) / 10000 AS price_usd,
    raw_hex,
    decompressed_offset,
    source_sha256,
    source_complete,
    observed_at
FROM us_nasdaq_itch_messages
WHERE source_sha256 = 'fdf8133e8aa56e888a57dcdc9048142890a7bac346dee158f16fee233c2c396e'
  AND message_index >= 309047
  AND message_index <= 659882
ORDER BY message_index;
