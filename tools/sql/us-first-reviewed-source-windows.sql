-- 首批复核窗口，保留原代码、供应商及二十列，不拼接为统一复权序列。
-- MTNB 的 2024-08-28、29、30 是已确认停牌日，不生成 K 线。
-- BURUD 是明确窗口内的临时代码；BURU 的前身阶段不代表同一业务历史。
-- USBC 的 TIINGO 单日与 YAHOO 窗口分开读取，调整基准不能直接衔接。
SELECT
    trade_date, symbol, provider, venue,
    open, high, low, close, volume,
    adj_open, adj_high, adj_low, adj_close, adj_volume,
    dividend, split_factor, currency, raw_price_basis,
    source_sha256, observed_at
FROM us_equity_daily
WHERE trade_date >= '2016-10-16'
  AND trade_date < '2026-10-17'
  AND (
      (symbol = 'MTNB' AND provider = 'TIINGO' AND source_sha256 IN (
          '49bf571122c48e12b96f5aef4c573f74f751875e8f42cff76366601bddf505fb',
          'ed46a6015a0656d455eb17414a553ca8eb55e636e7c2fb85265f3196e334ab99'
      ))
      OR (symbol = 'BURU' AND provider = 'YAHOO'
          AND source_sha256 = 'd3098a530d75701e352d9caa93f23dbd30f0086b59417a039d091b7c4a831076')
      OR (symbol = 'BURU' AND provider = 'TIINGO'
          AND source_sha256 = '12c1ce50db8f5b0ab4117e3f8ef57eda6ae1a7baea924db94f22aa5aca5d8ab0')
      OR (symbol = 'BURUD' AND provider = 'TIINGO'
          AND trade_date >= '2026-09-02' AND trade_date < '2026-09-12'
          AND source_sha256 = '53795f9e0f26c6ca94d3f6937ef5ad87aa080ee92501baa4d71f339fcd95d84c')
      OR (symbol = 'USBC' AND provider = 'YAHOO'
          AND source_sha256 = 'd91eb4dd2920ce9b72f08dbca19f2bb81b4370e54987b079512b8ed565a8e1f5')
      OR (symbol = 'USBC' AND provider = 'TIINGO'
          AND trade_date = '2025-02-19'
          AND source_sha256 = '9d02ad2f8b49d488e1f5b5f50c5c0a2d8820c21e68ced100a88fcf4384cbeca7')
  )
ORDER BY trade_date, symbol, provider
LIMIT 20001;
