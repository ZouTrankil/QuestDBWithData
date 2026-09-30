# D031 source contract review — `margin_zrz`

Implementation status: `implemented_not_verified`.

## Python call path checked

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/margin/margin_sync.py:160` calls `pro.slb_len(start_date=..., end_date=...)`. The wrapper has the Python-configured 150 requests/minute limit.
- `sync_margin_zrz` at line 297 uses the configured `20260101` fallback, resolves the completed end date, and requests the inclusive date range. It returns `unverified` for a missing or empty response; a non-empty `write_model` acknowledgement is also explicitly not physical verification.
- `reference_market.py` marks `margin_zrz` as `lifecycle='retired_source'`, `enabled=False`, with the description “已退役来源，历史只读”. Java keeps the owner `enabled=false,dailyEligible=false`; the implementation only exposes an explicit bounded operation against a named isolated target.
- The Python model `questdb/models/stock/margin.py` declares `trade_date`, `ob`, `auc_amount`, `repo_amount`, `repay_amount`, and `cb`. The physical timestamp is `trade_date`; the five metric columns are optional floats. Java preserves explicit source nulls rather than filling them with model defaults.

## Request and completion contract

- Endpoint: `slb_len`; one `start_date=YYYYMMDD,end_date=YYYYMMDD` request for an inclusive frozen range; no offset/cursor pagination.
- A request is limited to 366 calendar days, one source slice, at most 5,000 rows and a 4 MiB immutable raw receipt. A cap-sized response is rejected as potentially truncated.
- Exact six-field mapping and date-range validation are required. Rows are canonicalized by `trade_date`; duplicate dates, omitted fields, non-finite numeric values, invalid dates, source errors, and out-of-range rows fail closed.
- Empty provider results remain unverified and do not publish a replacement or advance a checkpoint. This follows the Python owner’s explicit empty-response behavior.
- The audited local snapshot shows rows from 2023-01-03 through 2025-07-25. The manual Java planner requires explicit `--to` no later than 2025-07-25; an empty isolated target also requires explicit `--from`. Current endpoint availability/permission has not been checked by this implementation.

## Physical schema and identity

The task-card/audit snapshot declares `trade_date TIMESTAMP`, five `DOUBLE` metrics, YEAR partitioning, WAL, and `DEDUP=false`; no formal business UPSERT key is declared. The Java natural identity is one row per `trade_date`. The normal formal dataset definition is read-only and no Flyway migration or formal-table mutation is introduced. Isolated targets must begin `java_d031_margin_zrz_` and retain YEAR/WAL/non-DEDUP.

