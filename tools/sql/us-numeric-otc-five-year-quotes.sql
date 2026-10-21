-- 只读查看三个已验收的五年原报价；零量记录保留，source_positive_volume 单独标出。
-- 原全段拒收任务仍待补；当前业务名称不代表整个窗口属于相同业务。
SELECT p.*, p.volume > 0 AS source_positive_volume
FROM us_equity_daily p
WHERE p.provider = 'YAHOO'
  AND p.trade_date >= '2021-10-16'
  AND p.trade_date <= '2026-10-16'
  AND ((p.symbol = 'APSI' AND p.source_sha256 = 'b2fffafa313ae7c9b62db369c2f84831cb54e129ba526e39609d20de2a1b5743')
    OR (p.symbol = 'BWMG' AND p.source_sha256 = '91136560f7384ddd3d41995e7d20bab23e07ae57f9fcb56d10c5a97fe22f073a')
    OR (p.symbol = 'BSPK' AND p.source_sha256 = 'd3b1c3533afd1a3adcaf4706a728c38d74c1bf83ad321681bf4a326cd5da9974'))
ORDER BY p.trade_date, p.symbol, p.provider;
