# D014 ETF 日线来源契约

## 2026-09-30 核对结果

- 来源为 `fund_daily`，实际两次有界 `trade_date` 请求分别返回 2026-09-28 的 2163 行、2026-09-29 的 2136 行。收据及原始字段指纹见 `source-probe-summary.json`。
- [Tushare 官方文档 127](https://tushare.pro/document/2?doc_id=127) 本次读取列出单次最多 5000 行，按代码或日期取数。价格元、成交量手、成交额千元、涨跌幅百分数。
- Python `etf_daily_sync.py` 使用 2000 行 offset 分页。Java 当前明确使用官方 5000 行上限的一日非分页请求，不发送未在该接口文档列出的 offset；触顶即拒绝完整性并保存原始响应，没有跨接口回退。
- 返回的 `158008.OF` 是实测来源行，不能仅保留 SH/SZ 后缀。完整键接受六位代码加 SH/SZ/OF；未知代码形式拒绝并留证。
- 来源字段值不改写，行按完整业务键排序、JSON 对象键排序后存收据并计算 SHA-256；来源返回顺序变化不会导致相同数据重复发送。

## 物理契约

- 只读查询 `table_columns('etf_daily')` 已保存 `physical-schema-20260930.json`。
- 完整业务键 `(ts_code, trade_date)` 显式对应物理去重键 `(ts_code, timestamp)`。
- `timestamp` 是 `TIMESTAMP_NS`，承载交易日的 UTC 零点；读写按纳秒整数，非零点值被时间契约拒绝。保持 YEAR/WAL/DEDUP。
- 11 列为 ts_code、timestamp、pre_close、open、high、low、close、change、pct_chg、vol、amount，数值空值原样保留。
- 写入仅允许 `java_d014_etf_daily_` 前缀隔离表；不对正式表建表或写入。

## 同步与管理

显式首次 from，默认 INCREMENTAL；仅同目标、完整收据和逐字段回读支持的连续覆盖推进 checkpoint。重读五个自然日修订区间，单次最多 366 日；SSE 已完成交易日与上海20:30截止沿用 Python 日程。BACKFILL/RECONCILE 不推进增量 checkpoint。包含管理计划、运行、恢复、状态、取消及读写组合接线。

后续已完成共享接线编译、四次实际隔离写读、独立11字段核对、取消恢复及未知发送回读。状态 verified，详见 final-review.md；人工复核 pending_review。
